package com.example.chat.security;

import com.example.chat.dto.ErrorResponse;
import com.example.chat.exception.ChatException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;

/**
 * HTTP-side rate limits for the endpoints that brute-force and spam traffic
 * targets: login, registration and friend requests.
 *
 * <p>Message sending is <em>not</em> handled here - it has two entry points
 * (REST and STOMP) and the limit belongs where both converge, in
 * {@code PersistentMessageService.sendMessage}. This filter only covers the
 * pure-HTTP surface.</p>
 *
 * <p>Registered as an ordinary servlet filter it runs after Spring Security's
 * chain, so a request that made it past authentication carries a real
 * {@link Principal} and is counted per user; unauthenticated attempts (a
 * credential-stuffing run against login, for example) are counted per client
 * IP, which is exactly the key an attacker cannot cheaply rotate.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN = "/api/auth/login";
    private static final String REGISTER = "/api/auth/register";
    private static final String FRIEND_REQUEST = "/api/friends/requests";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        RateLimiter.Decision decision = check(request);
        if (decision == null || decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(ChatException.RATE_LIMITED,
                        "Too many requests. Try again in " + decision.retryAfterSeconds() + " seconds."));
    }

    /** The rule that matches this request, or {@code null} when unrate-limited. */
    private RateLimiter.Decision check(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        return switch (request.getServletPath()) {
            case LOGIN -> rateLimiter.forLogin(clientKey(request));
            case REGISTER -> rateLimiter.forRegister(clientKey(request));
            case FRIEND_REQUEST -> rateLimiter.forFriendRequest(userOrClientKey(request));
            default -> null;
        };
    }

    private static String clientKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Prefer the authenticated identity: one user cannot escape a per-IP
     * budget by sharing a NAT or rotating addresses, and the abuse counter
     * follows the account that will be reviewed.
     */
    private static String userOrClientKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        return principal != null ? "user:" + principal.getName() : clientKey(request);
    }
}
