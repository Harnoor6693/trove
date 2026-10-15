package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The caller's own privacy tier forbids sending this payload to this endpoint
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6: HIGH payloads are refused against a cloud tier).
 *
 * <p>A 403 with slug {@code ai-policy-blocked}: the request is well formed, the endpoint is
 * reachable, and the server is refusing on the member's own instruction. It is raised before any
 * connection is opened — a refusal that leaked the payload on its way to being refused would be
 * worse than no refusal at all.
 *
 * <p>No route in this collapse step emits it. It is reachable through {@link AiGateway}, which
 * steps 13, 14 and 17 call, and it is in the spec's slug enum from the start so that the clients'
 * generated union does not change under them when step 13 lands.
 */
public class AiPolicyBlockedException extends AiException {

    private static final long serialVersionUID = 1L;

    public AiPolicyBlockedException(String message) {
        super(message, HttpStatus.FORBIDDEN, "ai-policy-blocked");
    }
}
