package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.households.HouseholdRepository;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.upload.MultipartFileValidator;
import finance.trove.receipts.upload.ReceiptImageProcessor;
import finance.trove.receipts.upload.ReceiptUploadService;
import finance.trove.receipts.upload.UploadTooLargeException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B3-12: two uploads into one household, each of which fits on its own and which together do
 * not, may not both be admitted.
 *
 * <p>The quota is a directory walk followed by a write. Without a per-household serialisation
 * point both uploads read the same usage, both pass, and both write. The storage here is a stand-in
 * whose {@code usage} makes the two callers meet before answering, so the interleaving that loses
 * is the one the test always gets; the transaction, the household row and the receipt rows are
 * real.
 */
class ReceiptQuotaRaceIT extends AuthFlowSupport {

    private static final ReceiptProperties PROPERTIES = new ReceiptProperties(1, 200_000L, 40,
        2000, 400, 0.85d, 2, Duration.ofHours(1));

    @Autowired
    private ReceiptRepository receipts;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("B3-12: parallel uploads near the limit never take a household past its quota")
    void parallelUploadsNeverExceedTheQuota() throws Exception {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "receipt-quota-race");
        ReceiptImageProcessor images = new ReceiptImageProcessor(PROPERTIES);
        byte[] content = ReceiptImageFixtures.jpeg(1_200, 900);
        ReceiptImageProcessor.Renditions rendered = images.renditions(content);
        long stored = (long) rendered.full().length + rendered.thumbnail().length;
        // Room for one stored upload, not two, and enough that the declared size passes too.
        long headroom = Math.max(stored, content.length);
        assertThat(headroom).as("the fixture must leave room for exactly one upload")
            .isLessThan(2 * stored);
        long quota = PROPERTIES.quotaBytes();
        AtomicLong used = new AtomicLong(quota - headroom);

        CyclicBarrier meet = new CyclicBarrier(2);
        LocalDiskStorage storage = mock(LocalDiskStorage.class);
        when(storage.usage(eq(StorageArea.RECEIPTS), eq(member.householdId())))
            .thenAnswer(invocation -> {
                try {
                    meet.await(2, TimeUnit.SECONDS);
                } catch (TimeoutException | BrokenBarrierException e) {
                    // swallow-ok: once one caller holds the household lock the other cannot
                    // arrive; from then on the barrier is broken and answers at once.
                }
                return used.get();
            });
        when(storage.put(eq(StorageArea.RECEIPTS), eq(member.householdId()), any(byte[].class),
            anyString())).thenAnswer(invocation -> {
                used.addAndGet(invocation.<byte[]>getArgument(2).length);
                return "receipts/" + member.householdId() + "/" + UUID.randomUUID() + ".jpg";
            });
        AiGateway gateway = mock(AiGateway.class);
        when(gateway.forUser(any())).thenReturn(mock(AiHandle.class));
        ReceiptUploadService service = new ReceiptUploadService(receipts,
            new MultipartFileValidator(PROPERTIES), images, storage, gateway,
            mock(ReceiptPipelineOrchestrator.class), mock(ReceiptEventPublisher.class),
            PROPERTIES, households);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> outcomes = List.of(
                pool.submit(() -> admitted(transaction, service, member, content)),
                pool.submit(() -> admitted(transaction, service, member, content)));

            long admitted = 0;
            for (Future<Boolean> outcome : outcomes) {
                admitted += outcome.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(admitted).as("exactly one of the two fits").isEqualTo(1);
            assertThat(used.get()).as("the household is never past its allowance")
                .isLessThanOrEqualTo(quota);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean admitted(TransactionTemplate transaction, ReceiptUploadService service,
                                    Session member, byte[] content) {
        try {
            transaction.executeWithoutResult(status -> service.upload(member.householdId(),
                member.userId(), new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
                    content), null));
            return true;
        } catch (UploadTooLargeException refused) {
            return false;
        }
    }
}
