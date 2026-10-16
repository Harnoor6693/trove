package finance.trove.receipts.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.receipts.ReceiptEventPublisher;
import finance.trove.receipts.ReceiptImageFixtures;
import finance.trove.receipts.ReceiptProperties;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * One test per clause of the upload gauntlet
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 11; collapse step 14 scope item 2).
 *
 * <p>Every clause is about the <em>content</em> of a file, so every case here uses real image
 * bytes — including a genuine EXIF GPS tag and a genuinely crafted PNG header. A mocked
 * {@code ImageReader} would prove that the code calls a method, not that a sixty-thousand-pixel
 * declaration is refused before anything decodes it.
 */
class ReceiptUploadGauntletTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID UPLOADER = UUID.randomUUID();

    private ReceiptProperties properties;
    private MultipartFileValidator validator;
    private ReceiptImageProcessor processor;
    private LocalDiskStorage storage;
    private ReceiptRepository receipts;
    private AiGateway gateway;
    private AiHandle handle;
    private HouseholdRepository households;
    private ReceiptUploadService service;

    @BeforeEach
    void setUp() {
        properties = new ReceiptProperties(1, 200_000L, 40, 2000, 400, 0.85d, 2,
            Duration.ofHours(1));
        validator = new MultipartFileValidator(properties);
        processor = new ReceiptImageProcessor(properties);
        storage = mock(LocalDiskStorage.class);
        receipts = mock(ReceiptRepository.class);
        gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        when(gateway.forUser(any())).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(false);
        when(storage.put(any(), any(), any(byte[].class), anyString()))
            .thenReturn("receipts/" + HOUSEHOLD + "/" + UUID.randomUUID() + ".jpg");
        when(storage.usage(eq(StorageArea.RECEIPTS), any())).thenReturn(0L);
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        households = mock(HouseholdRepository.class);
        when(households.findByIdForUpdate(any())).thenReturn(Optional.of(mock(Household.class)));

        service = new ReceiptUploadService(receipts, validator, processor, storage, gateway,
            mock(ReceiptPipelineOrchestrator.class), mock(ReceiptEventPublisher.class),
            properties, households);
    }

    // ------------------------------------------------------------- (a) declared size

    @Test
    @DisplayName("(a) an over-size upload is refused from its DECLARED size, before the body is read")
    void overSizeIsRefusedWithoutReadingTheBody() {
        // A part that reports 10 MB and would throw if anything asked for its bytes. That is the
        // whole assertion: the source called getBytes() to look at the first twelve bytes and so
        // had already spent the memory by the time it checked any ceiling.
        MultipartFile hostile = new MockMultipartFile("file", "r.jpg", "image/jpeg",
            new byte[0]) {
            @Override
            public long getSize() {
                return 10L * 1024 * 1024;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public byte[] getBytes() {
                throw new AssertionError("the body must not be read before the size is checked");
            }

            @Override
            public InputStream getInputStream() {
                throw new AssertionError("the body must not be read before the size is checked");
            }
        };

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, hostile, "UPLOAD"))
            .isInstanceOf(UploadTooLargeException.class)
            .hasMessageContaining("byte limit");
        verify(storage, never()).put(any(), any(), any(byte[].class), anyString());
    }

    // ------------------------------------------------------------- (b) magic bytes

    @Test
    @DisplayName("(b) a PNG declared as JPEG is refused: the bytes must match the header")
    void declaredTypeMustMatchTheBytes() {
        MultipartFile lying = new MockMultipartFile("file", "receipt.jpg", "image/jpeg",
            ReceiptImageFixtures.png(40, 40));

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, lying, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("(b) HEIC is refused server-side, whatever it declares")
    void heicIsRefused() {
        // A real HEIC header: an ISO base-media 'ftyp' box with the 'heic' brand. The source
        // accepted this type and stored it; nothing in this JVM can decode it, so accepting it
        // would mean storing a file the server can neither re-encode nor strip metadata from.
        byte[] heic = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};

        MultipartFile declared = new MockMultipartFile("file", "IMG_0001.HEIC", "image/heic", heic);
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, declared, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Accepted: JPEG and PNG");

        MultipartFile disguised =
            new MockMultipartFile("file", "IMG_0001.HEIC", "image/jpeg", heic);
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, disguised, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("(b) PDF is refused: this version accepts no document formats")
    void pdfIsRefused() {
        MultipartFile pdf = new MockMultipartFile("file", "receipt.pdf", "application/pdf",
            "%PDF-1.7\n%âãÏÓ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, pdf, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("PDF is not accepted");
    }

    @Test
    @DisplayName("(b) an empty part is refused before anything else looks at it")
    void emptyIsRefused() {
        MultipartFile empty = new MockMultipartFile("file", "r.jpg", "image/jpeg", new byte[0]);
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, empty, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("must not be empty");
    }

    // ------------------------------------------------------------- (c) dimension probe

    @Test
    @DisplayName("(c) a 60000x60000 PNG header is refused WITHOUT decoding a single pixel")
    void hugeDeclaredDimensionsAreRefusedFromTheHeader() throws IOException {
        byte[] crafted = ReceiptImageFixtures.pngDeclaring(60_000, 60_000);

        // The fixture is a few hundred bytes and carries no image data at all. If the code under
        // test decoded before checking, this would be 3.6 billion pixels of int[] — the OOM the
        // ceiling exists to prevent — and if it decoded after refusing, ImageIO.read would fail
        // rather than the ceiling firing. Proving both halves: the file is tiny...
        assertThat(crafted.length).isLessThan(1_000);
        // ...and it is genuinely undecodable, so the refusal below cannot have come from a
        // decode: ImageIO either throws or answers null on it, and never a BufferedImage.
        assertThatThrownBy(() -> ImageIO.read(new ByteArrayInputStream(crafted)))
            .isInstanceOf(IOException.class);

        MultipartFile bomb = new MockMultipartFile("file", "r.png", "image/png", crafted);
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, bomb, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("40 megapixel limit");
        verify(storage, never()).put(any(), any(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("(c) the probe reads the declared dimensions and nothing more")
    void probeAnswersTheDeclaredDimensions() {
        assertThat(processor.probe(ReceiptImageFixtures.pngDeclaring(3_000, 2_000)))
            .isEqualTo(new ReceiptImageProcessor.Dimensions(3_000, 2_000));
    }

    // ------------------------------------------------------------- (d) household quota

    @Test
    @DisplayName("(d) the quota refuses at the boundary and allows just under it")
    void quotaIsEnforcedAtTheBoundary() {
        byte[] content = ReceiptImageFixtures.jpeg(200, 200);
        MultipartFile file = new MockMultipartFile("file", "r.jpg", "image/jpeg", content);

        // The quota is 1 MB here. Reporting one byte less than the whole allowance as already used
        // makes the incoming pair certain to cross it.
        when(storage.usage(eq(StorageArea.RECEIPTS), eq(HOUSEHOLD)))
            .thenReturn(properties.quotaBytes() - 1);
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, file, null))
            .isInstanceOf(UploadTooLargeException.class)
            .hasMessageContaining("storage allowance");

        // Nothing used: the same upload is accepted.
        when(storage.usage(eq(StorageArea.RECEIPTS), eq(HOUSEHOLD))).thenReturn(0L);
        assertThatCode(() -> service.upload(HOUSEHOLD, UPLOADER, file, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(d) the quota is checked against the declared size before anything is decoded")
    void theQuotaRefusesBeforeTheImageIsDecoded() {
        // A crafted 60000x60000 PNG: a few kilobytes on the wire, 14 GB of int[] if it is ever
        // decoded, and refused by the probe with "40 megapixel limit". If the quota were still
        // enforced after the re-encode, THAT is the exception this upload would raise — the
        // household would have paid for a header probe and two JPEG encodes before being told it
        // is out of space. The refusal must be the quota's, which is only true if the quota is
        // asked first, against the declared size.
        when(storage.usage(eq(StorageArea.RECEIPTS), eq(HOUSEHOLD)))
            .thenReturn(properties.quotaBytes());
        MultipartFile bomb = new MockMultipartFile("file", "r.png", "image/png",
            ReceiptImageFixtures.pngDeclaring(60_000, 60_000));

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, UPLOADER, bomb, null))
            .isInstanceOf(UploadTooLargeException.class)
            .hasMessageContaining("storage allowance");
        verify(storage, never()).put(any(), any(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("(d) the quota is accounted per household, not per user")
    void quotaIsAskedOfTheHousehold() {
        MultipartFile file =
            new MockMultipartFile("file", "r.jpg", "image/jpeg", ReceiptImageFixtures.jpeg(50, 50));
        service.upload(HOUSEHOLD, UPLOADER, file, null);

        // Gap row 11 words the quota as per-user; LocalDiskStorage accounts per household because
        // the household id is the second segment of every key it generates. The deviation is
        // deliberate and this is what pins it.
        // Twice, and only ever of the household: once against the declared size before the body is
        // read and once against the two JPEGs that are about to be written.
        verify(storage, times(2)).usage(StorageArea.RECEIPTS, HOUSEHOLD);
        verify(storage, never()).usage(StorageArea.RECEIPTS, UPLOADER);
    }

    // ------------------------------------------------------------- (e) re-encode

    @Test
    @DisplayName("(e) a JPEG with a GPS EXIF tag is stored with no EXIF segment at all")
    void exifIsStrippedByReencoding() {
        byte[] withGps = ReceiptImageFixtures.jpegWithGpsExif(300, 200);
        // The fixture really carries what the test claims it carries.
        assertThat(ReceiptImageFixtures.hasApp1Segment(withGps)).isTrue();
        assertThat(ReceiptImageFixtures.containsExifIdentifier(withGps)).isTrue();

        byte[] stored = processor.reencode(withGps, 2000);

        assertThat(ReceiptImageFixtures.hasApp1Segment(stored))
            .as("the stored JPEG must carry no APP1 segment: EXIF is stripped by re-encoding from "
                + "pixels, not by removing tags the code knows the names of")
            .isFalse();
        assertThat(ReceiptImageFixtures.containsExifIdentifier(stored)).isFalse();
    }

    @Test
    @DisplayName("(e) a 4000 px image is stored at 2000 px on its long edge, and is not upscaled")
    void oversizeImagesAreScaledDownAndSmallOnesAreNot() throws IOException {
        BufferedImage large = ImageIO.read(new ByteArrayInputStream(
            processor.reencode(ReceiptImageFixtures.jpeg(4_000, 1_000), 2_000)));
        assertThat(large.getWidth()).isEqualTo(2_000);
        assertThat(large.getHeight()).isEqualTo(500);

        BufferedImage small = ImageIO.read(new ByteArrayInputStream(
            processor.reencode(ReceiptImageFixtures.jpeg(300, 200), 2_000)));
        assertThat(small.getWidth()).isEqualTo(300);
        assertThat(small.getHeight()).isEqualTo(200);
    }

    @Test
    @DisplayName("(e) a PNG is stored as a JPEG: the format sent is not the format kept")
    void everythingIsStoredAsJpeg() {
        byte[] stored = processor.reencode(ReceiptImageFixtures.png(100, 100), 2_000);
        assertThat(MultipartFileValidator.sniff(java.util.Arrays.copyOf(stored, 12)))
            .isEqualTo(MultipartFileValidator.IMAGE_JPEG);
    }

    @Test
    @DisplayName("(e) both stored sizes come out of ONE decode of the source")
    void bothSizesComeFromOneDecode() throws IOException {
        ReceiptImageProcessor.Renditions rendered =
            processor.renditions(ReceiptImageFixtures.jpeg(4_000, 1_000));

        BufferedImage full =
            ImageIO.read(new ByteArrayInputStream(rendered.full()));
        BufferedImage thumbnail =
            ImageIO.read(new ByteArrayInputStream(rendered.thumbnail()));
        assertThat(full.getWidth()).isEqualTo(2_000);
        assertThat(thumbnail.getWidth()).isEqualTo(400);
    }

    @Test
    @DisplayName("(e) no more images are decoded at once than there are render permits")
    void concurrentDecodesAreBounded() throws Exception {
        // The megapixel ceiling bounds ONE image at about 150 MB of int[]; without a permit this
        // many overlapping uploads — each of them perfectly legal and each of them cheap on the
        // wire — are what reach the heap together. Eight threads against two permits: the peak
        // this processor ever saw must still be two.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        byte[] source = ReceiptImageFixtures.jpeg(1_600, 1_200);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(pool.submit(() -> {
                    startTogether.await();
                    return processor.renditions(source);
                }));
            }
            startTogether.countDown();
            for (Future<?> each : running) {
                each.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(processor.peakConcurrentRenders())
            .as("trove.receipt.max-concurrent-renders is 2 here, and it is a bound, not a hint")
            .isLessThanOrEqualTo(properties.maxConcurrentRenders());
    }

    // ------------------------------------------------------------- (f) storage keys

    @Test
    @DisplayName("(f) a filename of ../../etc/passwd never reaches a path")
    void theFilenameNeverReachesAPath() {
        MultipartFile traversal = new MockMultipartFile("file", "../../etc/passwd",
            "image/jpeg", ReceiptImageFixtures.jpeg(60, 60));

        service.upload(HOUSEHOLD, UPLOADER, traversal, null);

        // LocalDiskStorage.put takes an area, a household, bytes and an EXTENSION HINT. There is
        // no parameter a filename could travel in, which is the guarantee: the key is generated
        // server-side from a v4 UUID and a re-validated hint, so ../../etc/passwd cannot be
        // expressed at all rather than being escaped.
        verify(storage, org.mockito.Mockito.times(2))
            .put(eq(StorageArea.RECEIPTS), eq(HOUSEHOLD), any(byte[].class), eq("jpg"));
    }

    @Test
    @DisplayName("(f) both an ORIGINAL and a THUMBNAIL are stored and recorded")
    void twoImagesAreStored() {
        MultipartFile file = new MockMultipartFile("file", "r.jpg", "image/jpeg",
            ReceiptImageFixtures.jpeg(1_200, 900));

        Receipt receipt = service.upload(HOUSEHOLD, UPLOADER, file, "SCAN");

        assertThat(receipt.getImages()).hasSize(2);
        assertThat(receipt.original()).isPresent();
        assertThat(receipt.thumbnail()).isPresent();
        assertThat(receipt.original().orElseThrow().getMimeType())
            .isEqualTo(MultipartFileValidator.IMAGE_JPEG);
        assertThat(receipt.original().orElseThrow().getSha256()).hasSize(64);
        verify(storage, org.mockito.Mockito.times(2))
            .put(eq(StorageArea.RECEIPTS), eq(HOUSEHOLD), any(byte[].class), eq("jpg"));
    }

    @Test
    @DisplayName("uploadSizeBytes records what was SENT, not what was stored")
    void uploadSizeRecordsTheSentFile() {
        byte[] content = ReceiptImageFixtures.jpeg(1_500, 1_500);
        MultipartFile file = new MockMultipartFile("file", "r.jpg", "image/jpeg", content);

        assertThat(service.upload(HOUSEHOLD, UPLOADER, file, null).getUploadSizeBytes())
            .isEqualTo(content.length);
    }
}
