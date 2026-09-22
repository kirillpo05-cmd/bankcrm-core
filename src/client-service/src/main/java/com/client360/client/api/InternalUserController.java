package com.client360.client.api;

import com.client360.client.api.ClientResponse.UserSummary;
import com.client360.client.persistence.UserRepository;
import com.client360.common.api.ApiException;
import com.client360.common.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Display names for staff ids, so another service can render an author or an assignee without
 * owning a user table (SPEC.md §3.1).
 *
 * <p>Users are corporate directory data, not client PII — the same reason {@code users.email} is a
 * plaintext column (§9.2.3). Any authenticated caller may resolve a name they already hold the id
 * for; this exposes nothing they could not read off an interaction they can already see.
 *
 * <p>The id set is bounded and required: there is no "list every user" here, because a directory
 * dump is a different request with a different permission ({@code user:read}).
 */
@RestController
@RequestMapping("/api/v1/internal/users")
public class InternalUserController {

    /** One timeline page resolves at most this many distinct authors. */
    private static final int MAX_IDS = 100;

    private final UserRepository users;

    public InternalUserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<UserSummary> byIds(@RequestParam List<UUID> ids, CurrentUser caller) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_IDS) {
            throw ApiException.validation("ids", "at most " + MAX_IDS + " ids per request");
        }
        return users.findAllByIds(ids).stream()
                .map(user -> new UserSummary(user.id(), user.fullName()))
                .toList();
    }
}
