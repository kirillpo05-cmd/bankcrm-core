package com.client360.common.security;

import com.client360.common.api.ErrorCodes;
import com.client360.common.api.ErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders filter-chain rejections in the §4.3 envelope, with the three {@code 401} codes of §4.4:
 * {@code TOKEN_MISSING}, {@code TOKEN_EXPIRED} and {@code TOKEN_INVALID}.
 */
@Component
public class AuthErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ErrorResponses errors;

    public AuthErrorHandlers(ErrorResponses errors) {
        this.errors = errors;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            errors.write(
                    request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_MISSING, "Authentication required.");
        } else if (isExpired(ex)) {
            errors.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.TOKEN_EXPIRED,
                    "Access token expired. Refresh and retry.");
        } else {
            errors.write(
                    request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_INVALID, "Access token is invalid.");
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        errors.write(request, response, HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, "Action not permitted.");
    }

    private static boolean isExpired(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation) {
                return validation.getErrors().stream()
                        .anyMatch(e ->
                                e.getDescription() != null && e.getDescription().startsWith("Jwt expired"));
            }
        }
        return false;
    }
}
