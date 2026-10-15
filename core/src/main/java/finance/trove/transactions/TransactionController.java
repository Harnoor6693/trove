package finance.trove.transactions;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.common.plugin.TransactionType;
import finance.trove.transactions.dto.ManualTransactionRequest;
import finance.trove.transactions.dto.TransactionPatch;
import finance.trove.transactions.dto.TransactionResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The household's transaction ledger ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims. The pre-collapse controller
 * took its user id from a client-supplied header instead — finding G1 — and
 * {@code SourceConventionTest} now fails the build if such a header appears in a controller at
 * all, which is why this paragraph names it in prose rather than quoting it.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final TransactionQueryService queries;
    private final TransactionService transactions;

    public TransactionController(TransactionQueryService queries,
                                 TransactionService transactions) {
        this.queries = queries;
        this.transactions = transactions;
    }

    /**
     * {@code GET /v1/transactions} — one page of the ledger.
     *
     * <p>Every parameter is optional and every one is validated in {@link TransactionQuery#of},
     * which is also where the sort allowlist is applied. A value outside a bound is 400 rather
     * than being clamped: a client that computed {@code limit=1000} asked for a thousand rows and
     * would page wrongly if it were quietly given five hundred.
     */
    @GetMapping
    public ApiResponse<List<TransactionResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) TransactionType type,
        @RequestParam(required = false) UUID accountId,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) String q) {

        TransactionQuery query =
            TransactionQuery.of(page, limit, sort, from, to, type, accountId, categoryId, q);
        PagedResult<Transaction> result = queries.search(principal.getHouseholdId(), query);
        return ApiResponse.paged(result.content().stream().map(TransactionResponse::of).toList(),
            PaginationMeta.from(result));
    }

    /** {@code POST /v1/transactions} — manual entry onto a MANUAL account (gap P2-13). */
    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody ManualTransactionRequest request) {
        Transaction created = transactions.createManual(principal.getHouseholdId(),
            principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(TransactionResponse.of(created)));
    }

    /** {@code GET /v1/transactions/{id}} — 404 for another household's id, never 403. */
    @GetMapping("/{id}")
    public ApiResponse<TransactionResponse> get(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            TransactionResponse.of(transactions.require(principal.getHouseholdId(), id)));
    }

    /**
     * {@code PATCH /v1/transactions/{id}} — category, merchant, notes, tags, transfer link.
     *
     * <p>The body is read as a tree rather than bound to a record because <em>presence</em> is
     * part of the contract here: {@code {"transferGroupId": null}} unlinks a transfer and a body
     * that omits the field leaves it alone, and a record cannot tell those two apart. See
     * {@link TransactionPatch}.
     */
    @PatchMapping("/{id}")
    public ApiResponse<TransactionResponse> patch(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @RequestBody JsonNode body) {
        return ApiResponse.ok(TransactionResponse.of(
            transactions.patch(principal.getHouseholdId(), principal.getUserId(), id,
                TransactionPatch.from(body))));
    }

    /** {@code DELETE /v1/transactions/{id}} — manual rows only; a synced row is 409. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        transactions.delete(principal.getHouseholdId(), id);
        return ApiResponse.<Void>ok(null);
    }
}
