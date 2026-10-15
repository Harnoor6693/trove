package finance.trove.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The account surface of collapse step 6 ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3; gap
 * P2-13).
 *
 * <p>Cross-household isolation for these routes lives in {@code AccessControlIT}, which owns the
 * two-household harness. What is asserted here is the behaviour of the routes themselves: the
 * shape they return, what they refuse, and the two things about them that are easy to get wrong —
 * that a credential blob never reaches a response, and that a currency is never a literal.
 */
class AccountIT extends LedgerSupport {

    @Test
    @DisplayName("A MANUAL account needs no credentials and comes back without any")
    void aManualAccountIsCredentialLess() {
        Session admin = loginBootstrapAdmin();

        String accountId = createManualAccount(admin, "Cash");
        JsonNode account = Json.data(
            get(appUrl("/v1/accounts/" + accountId), admin.accessToken()).body());

        assertThat(account.path("sourceKind").asString()).isEqualTo("MANUAL");
        assertThat(account.path("syncStatus").asString()).isEqualTo("PENDING");
        assertThat(account.path("displayName").asString()).isEqualTo("Cash");
        assertThat(account.path("householdId").asString())
            .isEqualTo(admin.householdId().toString());
        assertThat(account.path("createdBy").asString())
            .as("createdBy is audit, not scope")
            .isEqualTo(admin.userId().toString());
        assertThat(account.has("configEncrypted"))
            .as("the response type has no field for the source credentials at all — not one that "
                + "is nulled out on the way through")
            .isFalse();
        assertThat(account.has("externalAccountId"))
            .as("the provider's own identifier is server-side only")
            .isFalse();
        // The database column is nullable and stays null for a manual account, which is the fix
        // that makes a credential-less account insertable at all (§2.2).
        assertThat(jdbcTemplate.queryForObject(
            "SELECT config_encrypted FROM connected_account WHERE id = ?", String.class,
            UUID.fromString(accountId)))
            .isNull();
    }

    @Test
    @DisplayName("A household may hold several manual accounts — transfers need at least two")
    void severalManualAccountsCoexist() {
        Session admin = loginBootstrapAdmin();

        String cash = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String cheques = createManualAccount(admin, "Cheques " + UUID.randomUUID());

        assertThat(cash).isNotEqualTo(cheques);
        assertThat(accountIds(admin)).contains(cash, cheques);
    }

    @Test
    @DisplayName("An omitted currency is the creating member's own, never a 'CAD' literal")
    void currencyDefaultsToTheMembersOwn() {
        Session admin = loginBootstrapAdmin();
        String americanEmail = uniqueEmail("account-usd");
        HttpResponse<String> registered = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", createInvite(admin, null), "email", americanEmail,
                "password", MEMBER_PASSWORD, "displayName", "US Member", "currency", "USD",
                "country", "US", "timezone", "America/New_York"),
            "X-Trove-Auth-Mode", "bearer");
        assertThat(registered.statusCode()).isEqualTo(201);
        Session american = loginBearer(americanEmail, MEMBER_PASSWORD);

        HttpResponse<String> response = postJson(appUrl("/v1/accounts"),
            Json.object("sourceKind", "MANUAL", "displayName", "Wallet", "accountType", "OTHER"),
            "Authorization", "Bearer " + american.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Json.data(response.body()).path("currency").asString())
            .as("§9 risk 5: a hard-coded currency silently mis-states every amount ever stored "
                + "against the account, and no later edit can repair it")
            .isEqualTo("USD");
    }

    @Test
    @DisplayName("An explicit currency is honoured")
    void anExplicitCurrencyIsUsed() {
        Session admin = loginBootstrapAdmin();

        String accountId = createAccount(admin, MANUAL, "US Savings " + UUID.randomUUID(),
            "SAVINGS", "USD");

        assertThat(Json.data(get(appUrl("/v1/accounts/" + accountId), admin.accessToken()).body())
            .path("currency").asString()).isEqualTo("USD");
    }

    @Test
    @DisplayName("CREDIT_CARD is accepted — the old CHECK spelled it CREDIT and rejected every one")
    void creditCardIsAValidAccountType() {
        Session admin = loginBootstrapAdmin();

        String accountId = createAccount(admin, MANUAL, "Card " + UUID.randomUUID(),
            "CREDIT_CARD", "CAD");

        assertThat(Json.data(get(appUrl("/v1/accounts/" + accountId), admin.accessToken()).body())
            .path("accountType").asString()).isEqualTo("CREDIT_CARD");
    }

    @Test
    @DisplayName("PUT renames and reclassifies; it cannot change the currency or the source kind")
    void updateChangesPresentationOnly() {
        Session admin = loginBootstrapAdmin();
        String accountId = createAccount(admin, MANUAL, "Old name " + UUID.randomUUID(), "OTHER",
            "CAD");

        HttpResponse<String> response = putJson(appUrl("/v1/accounts/" + accountId),
            Json.object("displayName", "New name", "institution", "Example Credit Union",
                "accountType", "SAVINGS"),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode account = Json.data(response.body());
        assertThat(account.path("displayName").asString()).isEqualTo("New name");
        assertThat(account.path("institution").asString()).isEqualTo("Example Credit Union");
        assertThat(account.path("accountType").asString()).isEqualTo("SAVINGS");
        assertThat(account.path("currency").asString()).isEqualTo("CAD");
        assertThat(account.path("sourceKind").asString()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("A blank display name is 400 with the field named")
    void aBlankNameIsRejected() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> response = postJson(appUrl("/v1/accounts"),
            Json.object("sourceKind", "MANUAL", "displayName", "   ", "accountType", "OTHER"),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.parse(response.body()).path("error").path("fieldErrors").toString())
            .contains("displayName");
    }

    @Test
    @DisplayName("Deleting an account takes its transactions with it")
    void deletingAnAccountCascadesToItsTransactions() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Doomed " + UUID.randomUUID());
        String transactionId = createManualTransaction(admin, accountId, "9.99", "2026-09-04",
            "DEBIT", "Something").path("id").asString();

        assertThat(delete(appUrl("/v1/accounts/" + accountId), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);

        assertThat(get(appUrl("/v1/accounts/" + accountId), admin.accessToken()).statusCode())
            .isEqualTo(404);
        assertThat(get(appUrl("/v1/transactions/" + transactionId), admin.accessToken())
            .statusCode())
            .as("ON DELETE CASCADE, which is why DELETE /v1/transactions/{id} may refuse a synced "
                + "row: disconnecting the account is how one goes away")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("An unknown account id is 404 on every by-id route")
    void unknownIdsAreNotFound() {
        Session admin = loginBootstrapAdmin();
        String unknown = "/v1/accounts/" + unknownId();
        String bearer = "Bearer " + admin.accessToken();

        assertThat(get(appUrl(unknown), admin.accessToken()).statusCode()).isEqualTo(404);
        assertThat(putJson(appUrl(unknown),
            Json.object("displayName", "x", "accountType", "OTHER"),
            "Authorization", bearer).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl(unknown), null, "Authorization", bearer).statusCode())
            .isEqualTo(404);
        assertThat(postJson(appUrl(unknown + "/sync"), null, "Authorization", bearer).statusCode())
            .isEqualTo(404);
        assertThat(get(appUrl(unknown + "/sync-logs"), admin.accessToken()).statusCode())
            .isEqualTo(404);
    }

    @Test
    @DisplayName("Syncing an account whose kind has no registered source is 409, not 500")
    void syncingWithNoRegisteredSourceIsAConflict() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());

        HttpResponse<String> response = postJson(appUrl("/v1/accounts/" + accountId + "/sync"),
            null, "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT sync_status FROM connected_account WHERE id = ?", String.class,
            UUID.fromString(accountId)))
            .as("a refused sync must not leave the account claimed")
            .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("An account with no runs has an empty sync history")
    void syncLogsStartEmpty() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Fresh " + UUID.randomUUID());

        HttpResponse<String> response =
            get(appUrl("/v1/accounts/" + accountId + "/sync-logs"), admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(response.body()))).isEmpty();
    }

    @Test
    @DisplayName("The listing is the household's, and a mate sees the same accounts")
    void theListingIsShared() {
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "account-mate");
        String accountId = createManualAccount(admin, "Shared " + UUID.randomUUID());

        assertThat(accountIds(mate))
            .as("one shared ledger per household: a per-user scope would pass every isolation "
                + "test and hide a partner's accounts")
            .contains(accountId);
    }

    private List<String> accountIds(Session session) {
        return Json.elements(Json.data(get(appUrl("/v1/accounts"), session.accessToken()).body()))
            .stream()
            .map(account -> account.path("id").asString())
            .toList();
    }
}
