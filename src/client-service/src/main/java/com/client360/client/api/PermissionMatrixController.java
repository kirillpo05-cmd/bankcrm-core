package com.client360.client.api;

import com.client360.client.persistence.PermissionRepository;
import com.client360.client.service.ClientAccess;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /permissions/matrix} (SPEC.md §9.3, RB-US-08).
 *
 * <p>Read from {@code role_permissions} — the same rows {@code DatabaseAccessPolicy} consults on
 * every request. That is the entire value of the endpoint: a matrix maintained anywhere else, in a
 * constant or a wiki page, documents what someone once intended, and the first migration that
 * changes a grant makes it a lie. This cannot drift, because there is nothing for it to drift from.
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionMatrixController {

    private final PermissionRepository permissions;
    private final ClientAccess access;

    public PermissionMatrixController(PermissionRepository permissions, ClientAccess access) {
        this.permissions = permissions;
        this.access = access;
    }

    /**
     * @return roles, each with the permissions it grants and the scope it grants them at
     */
    @GetMapping("/matrix")
    public MatrixResponse matrix(CurrentUser caller) {
        // §9.3: user:read. Held at any scope — the matrix is a statement about roles, not about
        // anyone's clients, so there is nothing for a scope to narrow.
        access.requireScope(caller, Permissions.USER_READ);

        Map<String, RoleRow> rows = new LinkedHashMap<>();
        for (PermissionRepository.MatrixEntry entry : permissions.matrix()) {
            rows.computeIfAbsent(entry.roleCode(), code -> new RoleRow(code, entry.roleName(), new ArrayList<>()))
                    .permissions()
                    .add(new Grant(entry.permissionCode(), entry.scope().name()));
        }
        return new MatrixResponse(
                List.copyOf(rows.values()),
                List.copyOf(Permissions.ALL).stream().sorted().toList());
    }

    /**
     * @param allPermissions every code the system recognizes, so a client can render the columns of
     *     the grid including the ones a given role does not hold
     */
    public record MatrixResponse(List<RoleRow> roles, List<String> allPermissions) {}

    public record RoleRow(String code, String name, List<Grant> permissions) {}

    public record Grant(String permission, String scope) {}
}
