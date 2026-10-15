package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and persists point-in-time net-worth snapshots, ONE ROW PER CURRENCY PRESENT
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 "Net worth", F2).
 *
 * <p><strong>Whose "today"?</strong> Household-scoped rows have no single caller for the nightly
 * job, so a decision was needed (task item 4 / gap P2-15). Both {@link #takeSnapshot(UUID, UUID)}
 * callers — {@code NetWorthController#snapshot} and {@link NetWorthDailySnapshotJob} — resolve the
 * snapshot date through THE HOUSEHOLD'S OWN ADMINISTRATOR's timezone (the first ADMIN member,
 * found via {@link UserRepository#findAllByHouseholdIdOrderByCreatedAtAsc}), never the calling
 * member's own zone. This is deliberate and identical between the two callers: it is what makes
 * the upsert-by-unique-key behaviour below actually collide on a same-day re-run instead of
 * silently drifting the two triggers a day apart in different timezones. The consequence — two
 * household mates in different timezones see the SAME {@code snapshot_date} for their shared
 * household — is a direct reading of "household_id is the scope" (§1.5), not a bug.
 *
 * <p>{@code user_id} ("created by") still records the real actor: the calling member for the
 * button, the household's own administrator for the job, since a scheduled write has no caller to
 * attribute it to.
 *
 * <p><strong>Re-running on the same local date UPDATES, never duplicates.</strong> The daily job
 * and {@code POST /v1/networth/snapshot} are the same write with different triggers: a member who
 * presses the button after the job already ran today must not get a 409 or a second row. Each
 * currency's existing row for {@code (householdId, today, currency)} is found first and its
 * figures replaced; only a currency with no existing row gets a new one.
 *
 * <p>{@code REQUIRES_NEW}, kept from the source: this snapshot must commit on its own even if the
 * caller's own transaction (the daily job's loop over every household) later fails for a different
 * household.
 */
@Service
public class NetWorthSnapshotService {

    private final AssetRepository assets;
    private final LiabilityRepository liabilities;
    private final NetWorthSnapshotRepository snapshots;
    private final UserRepository users;
    private final UserClock clock;

    public NetWorthSnapshotService(AssetRepository assets, LiabilityRepository liabilities,
                                   NetWorthSnapshotRepository snapshots, UserRepository users,
                                   UserClock clock) {
        this.assets = assets;
        this.liabilities = liabilities;
        this.snapshots = snapshots;
        this.users = users;
        this.clock = clock;
    }

    /**
     * @param triggeredByUserId the calling member, or {@code null} when the daily job is the
     *                          caller (no member to attribute the write to — the household's own
     *                          administrator is recorded instead)
     * @return one snapshot per currency this household holds at least one asset or liability in;
     *         empty if the household has neither
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NetWorthSnapshot> takeSnapshot(UUID householdId, UUID triggeredByUserId) {
        UUID adminId = requireHouseholdAdminId(householdId);
        LocalDate today = clock.today(adminId);
        UUID recordedBy = triggeredByUserId != null ? triggeredByUserId : adminId;

        Map<CurrencyCode, BigDecimal> assetTotals = toMap(
            assets.sumValueByHouseholdIdGroupByCurrency(householdId));
        Map<CurrencyCode, BigDecimal> liabilityTotals = toMap(
            liabilities.sumBalanceByHouseholdIdGroupByCurrency(householdId));

        EnumSet<CurrencyCode> currencies = EnumSet.noneOf(CurrencyCode.class);
        currencies.addAll(assetTotals.keySet());
        currencies.addAll(liabilityTotals.keySet());

        List<NetWorthSnapshot> result = new ArrayList<>();
        for (CurrencyCode currency : currencies) {
            BigDecimal totalAssets = currency.normalise(
                assetTotals.getOrDefault(currency, BigDecimal.ZERO));
            BigDecimal totalLiabilities = currency.normalise(
                liabilityTotals.getOrDefault(currency, BigDecimal.ZERO));

            NetWorthSnapshot existing = snapshots
                .findByHouseholdIdAndSnapshotDateAndCurrency(householdId, today, currency)
                .orElse(null);
            NetWorthSnapshot toSave;
            if (existing != null) {
                existing.setTotalAssets(totalAssets);
                existing.setTotalLiabilities(totalLiabilities);
                existing.setUserId(recordedBy);
                toSave = existing;
            } else {
                toSave = new NetWorthSnapshot(householdId, recordedBy, today, totalAssets,
                    totalLiabilities, currency, clock.now());
            }
            result.add(snapshots.save(toSave));
        }
        return result;
    }

    private static Map<CurrencyCode, BigDecimal> toMap(List<CurrencyTotal> totals) {
        Map<CurrencyCode, BigDecimal> byCurrency = new EnumMap<>(CurrencyCode.class);
        for (CurrencyTotal total : totals) {
            byCurrency.put(total.currency(), total.total());
        }
        return byCurrency;
    }

    /**
     * The household's own administrator, whose zone stands in for "the household's today" (see
     * the class javadoc). Falls back to the first member if a household somehow has none — every
     * household is guaranteed at least one ADMIN elsewhere in the application (the last-ADMIN
     * guard), so this is a defensive fallback, not the expected path.
     */
    private UUID requireHouseholdAdminId(UUID householdId) {
        List<User> members = users.findAllByHouseholdIdOrderByCreatedAtAsc(householdId);
        UUID adminId = members.stream().filter(member -> member.getRole() == UserRole.ADMIN)
            .map(User::getId).findFirst().orElse(null);
        if (adminId != null) {
            return adminId;
        }
        return members.stream().map(User::getId).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Household " + householdId + " has no members to resolve a snapshot date from"));
    }
}
