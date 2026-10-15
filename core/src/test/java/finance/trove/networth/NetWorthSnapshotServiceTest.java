package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.transactions.TransactionFixtures;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NetWorthSnapshotService}, at a mocked-repository level — the one place F2 (per-currency
 * aggregation, never a blended sum) and the same-day upsert rule can be proven by behaviour rather
 * than by status code.
 */
@ExtendWith(MockitoExtension.class)
class NetWorthSnapshotServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    @Mock
    private AssetRepository assets;

    @Mock
    private LiabilityRepository liabilities;

    @Mock
    private NetWorthSnapshotRepository snapshots;

    @Mock
    private UserRepository users;

    private NetWorthSnapshotService service(UserClock clock) {
        return new NetWorthSnapshotService(assets, liabilities, snapshots, users, clock);
    }

    /** A UTC-fixed clock and a single UTC household administrator — the common case. */
    private NetWorthSnapshotService service() {
        UUID adminId = UUID.randomUUID();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(
            List.of(NetWorthFixtures.member(adminId, HOUSEHOLD, UserRole.ADMIN,
                ZoneId.of("UTC"))));
        UserClock clock = new UserClock((UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
            Clock.fixed(Instant.parse("2026-09-04T23:30:00Z"), ZoneId.of("UTC")));
        return service(clock);
    }

    private void savePassesThrough() {
        when(snapshots.save(any(NetWorthSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Two currencies produce two snapshot rows, never one blended sum (F2)")
    void twoCurrenciesProduceTwoRowsNeverOneSum() {
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of(
            new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("100.00")),
            new CurrencyTotal(CurrencyCode.USD, new BigDecimal("50.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("20.00"))));
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(eq(HOUSEHOLD), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();

        List<NetWorthSnapshot> result = service().takeSnapshot(HOUSEHOLD, null);

        assertThat(result).hasSize(2);
        NetWorthSnapshot cad = byCurrency(result, CurrencyCode.CAD);
        NetWorthSnapshot usd = byCurrency(result, CurrencyCode.USD);
        assertThat(cad.getTotalAssets()).isEqualByComparingTo("100.00");
        assertThat(cad.getTotalLiabilities()).isEqualByComparingTo("20.00");
        assertThat(usd.getTotalAssets()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("A CAD asset and a USD liability produce two independent rows, never borrowing "
        + "the other's figures")
    void mixedCurrencyRowsAreIndependent() {
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("300.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.USD, new BigDecimal("75.00"))));
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(eq(HOUSEHOLD), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();

        List<NetWorthSnapshot> result = service().takeSnapshot(HOUSEHOLD, null);

        assertThat(result).hasSize(2);
        NetWorthSnapshot cad = byCurrency(result, CurrencyCode.CAD);
        NetWorthSnapshot usd = byCurrency(result, CurrencyCode.USD);
        assertThat(cad.getTotalAssets()).isEqualByComparingTo("300.00");
        assertThat(cad.getTotalLiabilities())
            .as("the household's only liability is in USD; it must not leak into the CAD row")
            .isEqualByComparingTo("0.00");
        assertThat(usd.getTotalLiabilities()).isEqualByComparingTo("75.00");
        assertThat(usd.getTotalAssets())
            .as("the household's only asset is in CAD; it must not leak into the USD row")
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("B2-08: a currency with a row today but no positions left is written as zero")
    void aVanishedCurrencyIsZeroedNotLeftStale() {
        NetWorthSnapshot staleUsd = new NetWorthSnapshot(HOUSEHOLD, UUID.randomUUID(),
            LocalDate.of(2026, 9, 4), new BigDecimal("50.00"), new BigDecimal("0.00"),
            CurrencyCode.USD, Instant.now());
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("100.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(snapshots.findAllByHouseholdIdAndSnapshotDate(HOUSEHOLD, LocalDate.of(2026, 9, 4)))
            .thenReturn(List.of(staleUsd));
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(eq(HOUSEHOLD), any(),
            eq(CurrencyCode.CAD))).thenReturn(Optional.empty());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(eq(HOUSEHOLD), any(),
            eq(CurrencyCode.USD))).thenReturn(Optional.of(staleUsd));
        savePassesThrough();

        List<NetWorthSnapshot> result = service().takeSnapshot(HOUSEHOLD, null);

        assertThat(byCurrency(result, CurrencyCode.USD).getTotalAssets())
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("An empty household produces no snapshot rows, not a zero row")
    void emptyHouseholdProducesNoRows() {
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());

        List<NetWorthSnapshot> result = service().takeSnapshot(HOUSEHOLD, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Re-running on the same local date UPDATES the existing row, never duplicates")
    void reRunningOnTheSameDateUpdatesNotDuplicates() {
        NetWorthSnapshot existing = TransactionFixtures.withId(
            new NetWorthSnapshot(HOUSEHOLD, UUID.randomUUID(), LocalDate.of(2026, 9, 4),
                new BigDecimal("10.00"), new BigDecimal("0.00"), CurrencyCode.CAD, Instant.now()),
            UUID.randomUUID());
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("999.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(HOUSEHOLD,
            LocalDate.of(2026, 9, 4), CurrencyCode.CAD)).thenReturn(Optional.of(existing));
        savePassesThrough();

        List<NetWorthSnapshot> result = service().takeSnapshot(HOUSEHOLD, null);

        assertThat(result).hasSize(1);
        ArgumentCaptor<NetWorthSnapshot> saved = ArgumentCaptor.forClass(NetWorthSnapshot.class);
        verify(snapshots).save(saved.capture());
        assertThat(saved.getValue().getId())
            .as("the SAME row is updated — the id does not change")
            .isEqualTo(existing.getId());
        assertThat(saved.getValue().getTotalAssets())
            .as("the second run's values win")
            .isEqualByComparingTo("999.00");
    }

    @Test
    @DisplayName("The snapshot date comes from the household's own ADMINISTRATOR's zone, "
        + "identically for the job and the button — a DST-adjacent divergence")
    void snapshotDateComesFromTheHouseholdAdminsZone() {
        // 23:30 UTC on 2026-11-01 is 2026-11-01 16:30 in America/Vancouver (still PDT, one hour
        // from the fall-back) and 2026-11-01 20:30 in America/Toronto (EDT) — same calendar date
        // in both, so use an instant where a REAL zone boundary differs instead: 2026-11-01 04:30
        // UTC is 2026-10-31 21:30 in Vancouver (previous day) and 2026-11-01 00:30 in Toronto
        // (already the next day) for the SAME instant.
        Instant instant = Instant.parse("2026-11-01T04:30:00Z");
        UUID vancouverAdmin = UUID.randomUUID();
        UUID torontoAdmin = UUID.randomUUID();
        UUID vancouverHousehold = UUID.randomUUID();
        UUID torontoHousehold = UUID.randomUUID();

        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(vancouverHousehold)).thenReturn(
            List.of(NetWorthFixtures.member(vancouverAdmin, vancouverHousehold, UserRole.ADMIN,
                ZoneId.of("America/Vancouver"))));
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(torontoHousehold)).thenReturn(
            List.of(NetWorthFixtures.member(torontoAdmin, torontoHousehold, UserRole.ADMIN,
                ZoneId.of("America/Toronto"))));
        when(assets.sumValueByHouseholdIdGroupByCurrency(any())).thenReturn(
            List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("1.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(any())).thenReturn(List.of());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(any(), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();

        Map<UUID, ZoneId> zones = Map.of(vancouverAdmin, ZoneId.of("America/Vancouver"),
            torontoAdmin, ZoneId.of("America/Toronto"));
        UserClock clock = new UserClock(
            (UserTimeZoneProvider) userId -> zones.getOrDefault(userId, ZoneId.of("UTC")),
            Clock.fixed(instant, ZoneId.of("UTC")));

        NetWorthSnapshot vancouverRow =
            service(clock).takeSnapshot(vancouverHousehold, null).get(0);
        NetWorthSnapshot torontoRow = service(clock).takeSnapshot(torontoHousehold, null).get(0);

        assertThat(vancouverRow.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(torontoRow.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("A February 29 in a leap year resolves correctly through the admin's zone")
    void leapDayResolvesCorrectly() {
        Instant leapDayEvening = Instant.parse("2028-02-29T23:30:00Z");
        UUID adminId = UUID.randomUUID();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(
            List.of(NetWorthFixtures.member(adminId, HOUSEHOLD, UserRole.ADMIN,
                ZoneId.of("UTC"))));
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("1.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(any(), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();
        UserClock clock = new UserClock((UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
            Clock.fixed(leapDayEvening, ZoneId.of("UTC")));

        NetWorthSnapshot row = service(clock).takeSnapshot(HOUSEHOLD, null).get(0);

        assertThat(row.getSnapshotDate()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("Aggregate sums are rounded to the currency's minor units, half-up")
    void aggregatesAreRoundedHalfUp() {
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("150.005"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(any(), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();

        NetWorthSnapshot row = service().takeSnapshot(HOUSEHOLD, null).get(0);

        assertThat(row.getTotalAssets()).isEqualByComparingTo("150.01");
    }

    @Test
    @DisplayName("createdBy records the calling member; the job (no caller) records the "
        + "household's own administrator instead")
    void createdByReflectsTheTrigger() {
        UUID adminId = UUID.randomUUID();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(
            List.of(NetWorthFixtures.member(adminId, HOUSEHOLD, UserRole.ADMIN,
                ZoneId.of("UTC"))));
        when(assets.sumValueByHouseholdIdGroupByCurrency(HOUSEHOLD))
            .thenReturn(List.of(new CurrencyTotal(CurrencyCode.CAD, new BigDecimal("1.00"))));
        when(liabilities.sumBalanceByHouseholdIdGroupByCurrency(HOUSEHOLD)).thenReturn(List.of());
        when(snapshots.findByHouseholdIdAndSnapshotDateAndCurrency(any(), any(), any()))
            .thenReturn(Optional.empty());
        savePassesThrough();
        UUID caller = UUID.randomUUID();
        UserClock clock = new UserClock((UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
            Clock.fixed(Instant.parse("2026-09-04T23:30:00Z"), ZoneId.of("UTC")));

        NetWorthSnapshot byButton = service(clock).takeSnapshot(HOUSEHOLD, caller).get(0);
        NetWorthSnapshot byJob = service(clock).takeSnapshot(HOUSEHOLD, null).get(0);

        assertThat(byButton.getUserId()).isEqualTo(caller);
        assertThat(byJob.getUserId()).isEqualTo(adminId);
    }

    private static NetWorthSnapshot byCurrency(List<NetWorthSnapshot> rows,
                                               CurrencyCode currency) {
        return rows.stream().filter(row -> row.getCurrency() == currency).findFirst()
            .orElseThrow(() -> new AssertionError("No row for " + currency));
    }
}
