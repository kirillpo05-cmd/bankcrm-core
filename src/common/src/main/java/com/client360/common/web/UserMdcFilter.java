package com.client360.common.web;

import com.client360.common.security.CurrentUsers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after the security chain and adds the authenticated {@code userId} to the log MDC. */
public class UserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentUsers.find().ifPresent(user -> MDC.put("userId", user.id().toString()));
        chain.doFilter(request, response);
    }
}
