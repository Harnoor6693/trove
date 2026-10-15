package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.networth.dto.AssetRequest;
import finance.trove.networth.dto.AssetUpdateRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link AssetService} — currency defaulting and the account-linking guard. */
@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private AssetRepository assets;

    @Mock
    private ConnectedAccountRepository accounts;

    @Mock
    private UserRepository users;

    private AssetService service() {
        return new AssetService(assets, accounts, users);
    }

    private void savePassesThrough() {
        when(assets.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("currency defaults to the CREATING member's own users.currency when omitted")
    void currencyDefaultsToCreatorsOwn() {
        User creator = NetWorthFixtures.member(USER, HOUSEHOLD, UserRole.MEMBER, ZoneId.of("UTC"),
            CurrencyCode.USD);
        when(users.findByIdAndHouseholdId(USER, HOUSEHOLD)).thenReturn(Optional.of(creator));
        savePassesThrough();
        AssetRequest request = new AssetRequest("Cash", AssetType.CASH, new BigDecimal("10.00"),
            null, null);

        Asset created = service().create(HOUSEHOLD, USER, request);

        assertThat(created.getCurrency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("An explicit currency on the request is used as-is, with no lookup needed")
    void explicitCurrencyIsUsedAsIs() {
        savePassesThrough();
        AssetRequest request = new AssetRequest("Cash", AssetType.CASH, new BigDecimal("10.00"),
            CurrencyCode.CAD, null);

        Asset created = service().create(HOUSEHOLD, USER, request);

        assertThat(created.getCurrency()).isEqualTo(CurrencyCode.CAD);
    }

    @Test
    @DisplayName("An accountId naming another household's account is 400 validation-error, "
        + "never a silently stored dangling reference")
    void foreignAccountIdIsRejectedOnCreate() {
        UUID foreignAccount = UUID.randomUUID();
        when(accounts.existsByIdAndHouseholdId(foreignAccount, HOUSEHOLD)).thenReturn(false);
        AssetRequest request = new AssetRequest("Cash", AssetType.CASH, new BigDecimal("10.00"),
            CurrencyCode.CAD, foreignAccount);

        assertThatThrownBy(() -> service().create(HOUSEHOLD, USER, request))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A null accountId is a plain manual entry — no lookup, no rejection")
    void nullAccountIdIsAllowed() {
        savePassesThrough();
        AssetRequest request = new AssetRequest("Cash", AssetType.CASH, new BigDecimal("10.00"),
            CurrencyCode.CAD, null);

        Asset created = service().create(HOUSEHOLD, USER, request);

        assertThat(created.getAccountId()).isNull();
    }

    @Test
    @DisplayName("Updating an asset in another household is 404, never 403")
    void updatingAForeignAssetIsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(assets.findByIdAndHouseholdId(assetId, HOUSEHOLD)).thenReturn(Optional.empty());
        AssetUpdateRequest request =
            new AssetUpdateRequest("Renamed", AssetType.CASH, new BigDecimal("5.00"), null);

        assertThatThrownBy(() -> service().update(HOUSEHOLD, assetId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("currency is never writable through an update")
    void updateNeverChangesCurrency() {
        Asset existing = new Asset(HOUSEHOLD, USER, "Cash", AssetType.CASH,
            new BigDecimal("10.00"), CurrencyCode.USD, null, Instant.now());
        when(assets.findByIdAndHouseholdId(any(), eq(HOUSEHOLD))).thenReturn(Optional.of(existing));
        savePassesThrough();
        AssetUpdateRequest request =
            new AssetUpdateRequest("Renamed", AssetType.CASH, new BigDecimal("20.00"), null);

        Asset updated = service().update(HOUSEHOLD, UUID.randomUUID(), request);

        assertThat(updated.getCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(updated.getValue()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Deleting an asset in another household is 404, never 403")
    void deletingAForeignAssetIsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(assets.deleteByIdAndHouseholdId(assetId, HOUSEHOLD)).thenReturn(0L);

        assertThatThrownBy(() -> service().delete(HOUSEHOLD, assetId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
