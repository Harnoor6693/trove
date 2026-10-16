package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The rows and bytes a tax export run writes (B2-14). */
class TaxExportGenerationServiceTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);

    private static Transaction transaction(String merchant, boolean pending) {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
            new BigDecimal("10.00"), CurrencyCode.CAD, TransactionType.DEBIT, "d", merchant,
            LocalDate.of(2025, 3, 4), Instant.EPOCH, pending, false, Instant.EPOCH);
    }

    @Test
    @DisplayName("The CSV starts with a UTF-8 BOM and leaves pending transactions out")
    void bomAndNoPendingRows() {
        TransactionQueryService transactions = mock(TransactionQueryService.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        TaxLineMappingService lines = mock(TaxLineMappingService.class);
        TaxExportRunRepository runs = mock(TaxExportRunRepository.class);
        TaxExportStatusUpdater status = mock(TaxExportStatusUpdater.class);
        TaxExportRun run = new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.CSV);
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));
        when(status.transition(any(), any(), any(), any())).thenReturn(true);
        when(lines.resolve(userId, Jurisdiction.CA)).thenReturn(Map.of());
        when(categories.findAllVisible(householdId)).thenReturn(List.of());
        when(transactions.findSpendableInRange(eq(householdId), any(), any()))
            .thenReturn(List.of(transaction("Épicerie", false), transaction("Still Pending", true)));
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("csv")))
            .thenReturn("exports/k.csv");
        when(status.complete(any(), any(), any(), anyLong())).thenReturn(true);

        new TaxExportGenerationService(runs, transactions, categories, lines,
            new CsvExportBuilder(), mock(PdfExportBuilder.class), storage, status)
            .generateAsync(runId, userId, householdId);

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq(StorageArea.EXPORTS), eq(householdId), content.capture(),
            eq("csv"));
        assertThat(content.getValue()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(content.getValue(), StandardCharsets.UTF_8);
        assertThat(text).contains("Épicerie").doesNotContain("Still Pending");
    }

    @Test
    @DisplayName("A failure records a fixed sentence, not the raw exception message (CI-50)")
    void aFailureRecordsAFixedSentence() {
        TransactionQueryService transactions = mock(TransactionQueryService.class);
        TaxLineMappingService lines = mock(TaxLineMappingService.class);
        TaxExportRunRepository runs = mock(TaxExportRunRepository.class);
        TaxExportStatusUpdater status = mock(TaxExportStatusUpdater.class);
        TaxExportRun run = new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.CSV);
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));
        when(status.transition(any(), any(), eq(TaxExportStatus.GENERATING), any()))
            .thenReturn(true);
        when(lines.resolve(userId, Jurisdiction.CA)).thenReturn(Map.of());
        when(transactions.findSpendableInRange(eq(householdId), any(), any()))
            .thenReturn(List.of());
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("csv")))
            .thenThrow(new IllegalStateException("Could not store /data/exports/k.csv"));

        new TaxExportGenerationService(runs, transactions, mock(CategoryRepository.class), lines,
            new CsvExportBuilder(), mock(PdfExportBuilder.class), storage, status)
            .generateAsync(runId, userId, householdId);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(status).transition(eq(runId), eq(userId), eq(TaxExportStatus.FAILED),
            reason.capture());
        assertThat(reason.getValue()).isNotBlank().doesNotContain("/").doesNotContain("exports");
    }
}
