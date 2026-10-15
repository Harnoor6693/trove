package finance.trove.categorisation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST /v1/categories}, {@code PUT /v1/categories/{id}}.
 *
 * <p>This is the G3 fix (docs/recovery/01-FINDINGS.md, docs/recovery/inventory/
 * categorisation-service.md §10): the old service bound the JPA {@code Category} entity itself
 * as the request body, so a client-supplied {@code id} matching an existing system category
 * performed a JPA merge over it, silently rewriting a shared taxonomy row and flipping it
 * editable. This record carries exactly the three fields a caller may set — {@code id},
 * {@code householdId}, {@code slug} and {@code createdBy} are never client input, at any layer.
 */
public record CategoryRequest(
    @NotBlank @Size(max = 100) String name,
    UUID parentId,
    @Size(max = 50) String icon) {
}
