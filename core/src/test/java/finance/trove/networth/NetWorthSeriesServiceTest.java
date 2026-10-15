package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.networth.dto.NetWorthPoint;
import finance.trove.networth.dto.NetWorthSeriesResponse;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link NetWorthSeriesService} — the read-only assembly of {@code GET /v1/networth/series}. */
@ExtendWith(MockitoExtension.class)
class NetWorthSeriesServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    @Mock
    private NetWorthSnapshotRepository snapshots;

    @Mock
    private UserRepository users;

    private final UserClock clock = new UserClock((UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
        Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneId.of("UTC")));

    private NetWorthSeriesService service() {
        return new NetWorthSeriesService(snapshots, users, clock);
    }

    private void callerHasCurrency(CurrencyCode currency) {
        User caller = NetWorthFixtures.member(CALLER, HOUSEHOLD, UserRole.MEMBER,
            ZoneId.of("UTC"), currency);
        when(users.findByIdAndHouseholdId(CALLER, HOUSEHOLD)).thenReturn(Optional.of(caller));
    }

    private static NetWorthSnapshot row(LocalDate date, CurrencyCode currency, String assetsAmt,
                                        String liabilitiesAmt) {
        return TransactionFixtures.withId(
            new NetWorthSnapshot(HOUSEHOLD, UUID.randomUUID(), date, new BigDecimal(assetsAmt),
                new BigDecimal(liabilitiesAmt), currency, Instant.now()), UUID.randomUUID());
    }

    @Test
    @DisplayName("primaryCurrency is the CALLER's own users.currency, never a literal")
    void primaryCurrencyIsTheCallers() {
        callerHasCurrency(CurrencyCode.USD);
        when(snapshots.findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            any(), any())).thenReturn(List.of());

        NetWorthSeriesResponse response =
            service().getSeries(HOUSEHOLD, CALLER, NetWorthRange.ONE_YEAR);

        assertThat(response.primaryCurrency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("An empty range answers an empty series list, and the route never writes")
    void emptyRangeIsAnEmptyList() {
        callerHasCurrency(CurrencyCode.CAD);
        when(snapshots.findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            any(), any())).thenReturn(List.of());

        NetWorthSeriesResponse response =
            service().getSeries(HOUSEHOLD, CALLER, NetWorthRange.ALL);

        assertThat(response.series()).isEmpty();
    }

    @Test
    @DisplayName("Two currencies produce two series, never one blended series (F2)")
    void twoCurrenciesProduceTwoSeries() {
        callerHasCurrency(CurrencyCode.CAD);
        when(snapshots.findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            any(), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 9, 1), CurrencyCode.CAD, "100.00", "0.00"),
                row(LocalDate.of(2026, 9, 1), CurrencyCode.USD, "50.00", "0.00")));

        NetWorthSeriesResponse response =
            service().getSeries(HOUSEHOLD, CALLER, NetWorthRange.ONE_YEAR);

        assertThat(response.series()).hasSize(2);
        assertThat(response.series()).extracting("currency")
            .containsExactlyInAnyOrder(CurrencyCode.CAD, CurrencyCode.USD);
    }

    @Test
    @DisplayName("Points within a series are ordered oldest first")
    void pointsAreOrderedOldestFirst() {
        callerHasCurrency(CurrencyCode.CAD);
        when(snapshots.findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            any(), any())).thenReturn(List.of(
                row(LocalDate.of(2026, 8, 1), CurrencyCode.CAD, "100.00", "0.00"),
                row(LocalDate.of(2026, 9, 1), CurrencyCode.CAD, "120.00", "0.00")));

        NetWorthSeriesResponse response =
            service().getSeries(HOUSEHOLD, CALLER, NetWorthRange.ONE_YEAR);

        List<LocalDate> dates =
            response.series().get(0).points().stream().map(NetWorthPoint::snapshotDate).toList();
        assertThat(dates).containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("netWorth on a point agrees with totalAssets minus totalLiabilities")
    void netWorthAgreesWithAssetsMinusLiabilities() {
        callerHasCurrency(CurrencyCode.CAD);
        when(snapshots.findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            any(), any())).thenReturn(
                List.of(row(LocalDate.of(2026, 9, 1), CurrencyCode.CAD, "500.00", "125.00")));

        NetWorthSeriesResponse response =
            service().getSeries(HOUSEHOLD, CALLER, NetWorthRange.ONE_YEAR);

        assertThat(response.series().get(0).points().get(0).netWorth())
            .isEqualByComparingTo("375.00");
    }
}
