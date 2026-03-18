package com.nilsson.lmo.api;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * <p>The {@code SecurityFilter} is a critical architectural component responsible for enforcing
 * mandatory access control across the Latent Model Organizer's internal API surface.
 * It implements a robust, lightweight authentication mechanism using a cryptographically
 * secure handshake token generated at application startup.</p>
 *
 * <p>Architectural Design:
 * <ul>
 *   <li><b>Defense-in-Depth:</b> Protects all sensitive {@code /api/*} endpoints while
 *   explicitly allowing {@code OPTIONS} preflight requests and non-API resources.</li>
 *   <li><b>Multi-Channel Extraction:</b> Supports token retrieval from both the
 *   {@code Authorization: Bearer <token>} header (primary) and {@code ?token=<token>}
 *   query parameters (fallback for SSE/EventSource support).</li>
 *   <li><b>Fail-Closed Security:</b> Requests lacking a valid token are immediately
 *   terminated with a {@code 401 Unauthorized} response before reaching downstream handlers.</li>
 * </ul>
 * </p>
 *
 * @see com.sun.net.httpserver.Filter
 */
public class SecurityFilter extends Filter {

    private final String handshakeToken;

    public SecurityFilter(String handshakeToken) {
        if (handshakeToken == null || handshakeToken.isBlank()) {
            throw new IllegalArgumentException("Handshake token cannot be null or empty.");
        }
        this.handshakeToken = handshakeToken;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        var requestURI = exchange.getRequestURI().getPath();

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod()) || !requestURI.startsWith("/api/")) {
            chain.doFilter(exchange);
            return;
        }

        if (isAuthorized(exchange)) {
            chain.doFilter(exchange);
        } else {
            sendUnauthorized(exchange);
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        return getTokenFromHeader(exchange)
                .or(() -> getTokenFromQuery(exchange))
                .map(handshakeToken::equals)
                .orElse(false);
    }

    private Optional<String> getTokenFromHeader(HttpExchange exchange) {
        return Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization"))
                .filter(h -> h.startsWith("Bearer "))
                .map(h -> h.substring(7));
    }

    private Optional<String> getTokenFromQuery(HttpExchange exchange) {
        var query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && "token".equals(pair[0])) {
                return Optional.of(pair[1]);
            }
        }
        return Optional.empty();
    }

    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        String response = "{\"status\":\"error\",\"message\":\"Unauthorized\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(401, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public String description() {
        return "Security filter for API endpoints";
    }
}
