package finance.trove.auth.dto;

import finance.trove.users.UserRole;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /v1/admin/users/{id}/role} — the role to give a member (journey bug F1-B2).
 *
 * @param role {@code ADMIN} or {@code MEMBER}; required, because a role change with no role is not
 *     a request this route can answer
 */
public record AdminRoleRequest(@NotNull UserRole role) {
}
