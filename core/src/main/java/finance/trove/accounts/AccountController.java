package finance.trove.accounts;

import finance.trove.accounts.dto.AccountRequest;
import finance.trove.accounts.dto.AccountResponse;
import finance.trove.accounts.dto.AccountUpdateRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.ingestion.SyncOrchestrator;
import finance.trove.ingestion.dto.SyncLogResponse;
import finance.trove.ingestion.simplefin.ExchangeSetupTokenService;
import finance.trove.ingestion.simplefin.dto.ExchangeSetupTokenRequest;
import finance.trove.ingestion.simplefin.dto.ExchangeSetupTokenResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's connected accounts ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3).
 *
 * <p>Identity is {@link TroveUserDetails#getHouseholdId()} and
 * {@link TroveUserDetails#getUserId()}, both from signed claims. The pre-collapse controller read
 * a client-supplied identity header — a value any caller could set — which is finding G1;
 * {@code SourceConventionTest} now fails the build if such a header appears in a controller at
 * all, which is why this paragraph names it in prose rather than quoting it.
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final SyncOrchestrator syncOrchestrator;
    private final ExchangeSetupTokenService exchangeSetupTokenService;

    public AccountController(AccountService accountService, SyncOrchestrator syncOrchestrator,
                             ExchangeSetupTokenService exchangeSetupTokenService) {
        this.accountService = accountService;
        this.syncOrchestrator = syncOrchestrator;
        this.exchangeSetupTokenService = exchangeSetupTokenService;
    }

    /** {@code GET /v1/accounts} — the household's accounts, newest first. */
    @GetMapping
    public ApiResponse<List<AccountResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(accountService.list(principal.getHouseholdId()).stream()
            .map(AccountResponse::of)
            .toList());
    }

    /** {@code POST /v1/accounts} — adds one; {@code sourceKind=MANUAL} needs no credentials. */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody AccountRequest request) {
        ConnectedAccount created = accountService.create(principal.getHouseholdId(),
            principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(AccountResponse.of(created)));
    }

    /**
     * {@code POST /v1/accounts/exchange-setup-token} — connects everything behind one SimpleFIN
     * setup token ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3).
     *
     * <p>Mapped here rather than on a controller of its own so that one class owns
     * {@code /v1/accounts}; the literal segment cannot collide with {@code /v1/accounts/{id}},
     * which has no POST.
     *
     * <p>Neither the setup token nor the access URL it is exchanged for appears in the response —
     * {@code ExchangeSetupTokenResponse} has no field they could appear in, the same way
     * {@code AccountResponse} has none for {@code config_encrypted}. 400
     * {@code outbound-url-rejected} is the egress policy refusing the URL the token decodes to,
     * naming the rule that refused it and never the resolved address.
     */
    @PostMapping("/exchange-setup-token")
    public ResponseEntity<ApiResponse<ExchangeSetupTokenResponse>> exchangeSetupToken(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody ExchangeSetupTokenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            exchangeSetupTokenService.exchange(principal.getHouseholdId(), principal.getUserId(),
                request)));
    }

    /** {@code GET /v1/accounts/{id}} — 404 for another household's id, never 403. */
    @GetMapping("/{id}")
    public ApiResponse<AccountResponse> get(@AuthenticationPrincipal TroveUserDetails principal,
                                            @PathVariable UUID id) {
        return ApiResponse.ok(
            AccountResponse.of(accountService.get(principal.getHouseholdId(), id)));
    }

    /** {@code PUT /v1/accounts/{id}} — label, institution and product type only. */
    @PutMapping("/{id}")
    public ApiResponse<AccountResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody AccountUpdateRequest request) {
        return ApiResponse.ok(AccountResponse.of(
            accountService.update(principal.getHouseholdId(), id, request)));
    }

    /** {@code DELETE /v1/accounts/{id}} — removes the account and its transactions. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        accountService.delete(principal.getHouseholdId(), id);
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/accounts/{id}/sync} — one synchronisation run.
     *
     * <p>409 {@code conflict} when the account is already claimed by another run, and when no
     * source is registered for its kind yet. A run that fetched but refused rows, or could not
     * reach its source at all, is a 200 carrying a {@code PARTIAL_FAILURE} or {@code FAILED}
     * log — the request succeeded in recording what happened.
     */
    @PostMapping("/{id}/sync")
    public ApiResponse<SyncLogResponse> sync(@AuthenticationPrincipal TroveUserDetails principal,
                                             @PathVariable UUID id) {
        return ApiResponse.ok(SyncLogResponse.of(
            syncOrchestrator.sync(principal.getHouseholdId(), principal.getUserId(), id)));
    }

    /** {@code GET /v1/accounts/{id}/sync-logs} — the account's recent runs, newest first. */
    @GetMapping("/{id}/sync-logs")
    public ApiResponse<List<SyncLogResponse>> syncLogs(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            syncOrchestrator.recentLogs(principal.getHouseholdId(), id).stream()
                .map(SyncLogResponse::of)
                .toList());
    }
}
