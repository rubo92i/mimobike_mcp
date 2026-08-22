package com.mimobike.knowledge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the two protected surfaces: {@code /mcp} (developer tokens)
 * and {@code /internal/*} (the separate reload token). Health endpoints stay
 * open for load balancers. Tokens are never logged, and the two credential
 * kinds are not interchangeable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BearerAuthFilter extends OncePerRequestFilter {

    private final AuthTokens tokens;

    public BearerAuthFilter(AuthTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.equals("/health") || path.startsWith("/health/")) {
            chain.doFilter(request, response);
            return;
        }

        if (path.equals("/mcp") || path.startsWith("/mcp/")) {
            String developer = tokens.developerFor(bearer(request));
            if (developer == null) {
                unauthorized(response);
                return;
            }
            DeveloperContext.set(developer);
            try {
                chain.doFilter(request, response);
            } finally {
                DeveloperContext.clear();
            }
            return;
        }

        if (path.startsWith("/internal/")) {
            if (!tokens.isReloadToken(bearer(request))) {
                unauthorized(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).strip();
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
