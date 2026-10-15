package finance.trove.networth;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.networth.dto.AssetRequest;
import finance.trove.networth.dto.AssetResponse;
import finance.trove.networth.dto.AssetUpdateRequest;
import finance.trove.networth.dto.LiabilityRequest;
import finance.trove.networth.dto.LiabilityResponse;
import finance.trove.networth.dto.LiabilityUpdateRequest;
import finance.trove.networth.dto.NetWorthSeriesResponse;
import finance.trove.networth.dto.NetWorthSnapshotResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's net-worth surface: snapshots, assets, liabilities
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.7, §6 "Net worth").
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, body or
 * header. A resource of another household is 404, never 403 (§9 risk 2).
 */
@RestController
@RequestMapping("/v1/networth")
public class NetWorthController {

    private final NetWorthSeriesService series;
    private final NetWorthSnapshotService snapshots;
    private final AssetService assetService;
    private final LiabilityService liabilityService;

    public NetWorthController(NetWorthSeriesService series, NetWorthSnapshotService snapshots,
                              AssetService assetService, LiabilityService liabilityService) {
        this.series = series;
        this.snapshots = snapshots;
        this.assetService = assetService;
        this.liabilityService = liabilityService;
    }

    /** {@code GET /v1/networth/series} — read-only; never takes a snapshot as a side effect. */
    @GetMapping("/series")
    public ApiResponse<NetWorthSeriesResponse> getSeries(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(name = "range", required = false, defaultValue = "1Y") NetWorthRange range) {
        return ApiResponse.ok(
            series.getSeries(principal.getHouseholdId(), principal.getUserId(), range));
    }

    /**
     * {@code POST /v1/networth/snapshot} — the same write the daily job performs, triggered by a
     * member instead of the clock. Re-running it on the same local date updates the existing
     * row(s) rather than duplicating or conflicting.
     */
    @PostMapping("/snapshot")
    public ResponseEntity<ApiResponse<List<NetWorthSnapshotResponse>>> takeSnapshot(
        @AuthenticationPrincipal TroveUserDetails principal) {
        List<NetWorthSnapshotResponse> body = snapshots
            .takeSnapshot(principal.getHouseholdId(), principal.getUserId()).stream()
            .map(NetWorthSnapshotResponse::of).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/assets")
    public ApiResponse<List<AssetResponse>> listAssets(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(assetService.list(principal.getHouseholdId()).stream()
            .map(AssetResponse::of).toList());
    }

    @PostMapping("/assets")
    public ResponseEntity<ApiResponse<AssetResponse>> createAsset(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody AssetRequest request) {
        Asset created = assetService.create(principal.getHouseholdId(), principal.getUserId(),
            request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(AssetResponse.of(created)));
    }

    @PutMapping("/assets/{id}")
    public ApiResponse<AssetResponse> updateAsset(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody AssetUpdateRequest request) {
        return ApiResponse.ok(
            AssetResponse.of(assetService.update(principal.getHouseholdId(), id, request)));
    }

    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(@AuthenticationPrincipal TroveUserDetails principal,
                                        @PathVariable UUID id) {
        assetService.delete(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/liabilities")
    public ApiResponse<List<LiabilityResponse>> listLiabilities(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(liabilityService.list(principal.getHouseholdId()).stream()
            .map(LiabilityResponse::of).toList());
    }

    @PostMapping("/liabilities")
    public ResponseEntity<ApiResponse<LiabilityResponse>> createLiability(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody LiabilityRequest request) {
        Liability created = liabilityService.create(principal.getHouseholdId(),
            principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(LiabilityResponse.of(created)));
    }

    @PutMapping("/liabilities/{id}")
    public ApiResponse<LiabilityResponse> updateLiability(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody LiabilityUpdateRequest request) {
        return ApiResponse.ok(LiabilityResponse.of(
            liabilityService.update(principal.getHouseholdId(), id, request)));
    }

    @DeleteMapping("/liabilities/{id}")
    public ApiResponse<Void> deleteLiability(@AuthenticationPrincipal TroveUserDetails principal,
                                             @PathVariable UUID id) {
        liabilityService.delete(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }
}
