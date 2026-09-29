package com.client360.client.api;

import com.client360.client.service.AuthService;
import com.client360.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessions (SPEC.md §9.3): {@code POST /auth/login}, {@code /auth/refresh}, {@code /auth/logout},
 * {@code /auth/logout-all} and {@code GET /me}.
 *
 * <p>Which of these need a bearer token is a decision, not an oversight. Login and refresh cannot —
 * they exist to produce one. Logout cannot either: an access token lives 15 minutes and a refresh
 * token eight hours, so the common case for signing out is a client whose access token has already
 * expired, and requiring a live one would leave the only way to end a session being to wait for it
 * to end. The refresh token presented in the body <em>is</em> the proof, and it is the single thing
 * being revoked. {@code /auth/logout-all} and {@code /me} act on the caller as a whole and therefore
 * do require authentication.
 *
 * <p>No endpoint here logs a request body (rule 10). One of these bodies is a password.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * §9.3. Constant-time by construction: a bcrypt comparison runs whether or not the address
     * exists, and one error code covers both (RB-BR-12).
     */
    @PostMapping("/auth/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        SessionResponse session = SessionResponse.of(auth.login(request.email(), request.password()));
        // A token must never be cached by a proxy or written to a browser's disk cache.
        return ResponseEntity.ok().headers(noStore()).body(session);
    }

    /** Rotation: the presented token is spent and a new one is returned (RB-BR-10). */
    @PostMapping("/auth/refresh")
    public ResponseEntity<SessionResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        SessionResponse session = SessionResponse.of(auth.refresh(request.refreshToken()));
        return ResponseEntity.ok().headers(noStore()).body(session);
    }

    /** Idempotent: an unknown or already revoked token is still {@code 204}. */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Every session for the caller, on every device (RB-US-04). */
    @PostMapping("/auth/logout-all")
    public ResponseEntity<Void> logoutAll(CurrentUser caller) {
        auth.logoutAll(caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller's identity, roles, teams, effective permissions and active grants — the SPA's
     * entire source of truth for what to render (§9.3).
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(CurrentUser caller) {
        return ResponseEntity.ok().headers(noStore()).body(MeResponse.of(auth.me(caller)));
    }

    private static HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        return headers;
    }

    /**
     * Shape only: present, and bounded above. Deliberately no minimum length — RB-BR-12's policy
     * governs what a password may be <em>set</em> to, and enforcing it here would answer "that is
     * not even a valid password for this system" to someone guessing, which is a different response
     * for a different reason and exactly what an enumeration script measures.
     */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String password) {

        /** Never let a password reach a log line through a generated toString. */
        @Override
        public String toString() {
            return "LoginRequest[email=***]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }
}
