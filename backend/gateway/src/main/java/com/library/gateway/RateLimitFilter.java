package com.library.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-client rate limiting for {@code /api/**}, answering 429 + {@code Retry-After} when exceeded.
 *
 * <p>The client key is the first {@code X-Forwarded-For} hop (set by the shell's static server, the
 * only browser-facing proxy here) or the socket address. In production, only trust that header from
 * known proxies.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final TokenBucketRateLimiter limiter;

    public RateLimitFilter(LibraryGatewayProperties properties) {
        this.limiter = new TokenBucketRateLimiter(
                properties.rateLimit().capacity(), properties.rateLimit().refillPerSecond(), System::nanoTime);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }
        TokenBucketRateLimiter.Decision decision = limiter.tryConsume(clientKey(exchange));
        if (decision.allowed()) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        String requestId = response.getHeaders().getFirst(RequestIdFilter.HEADER);
        String body = "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please slow down.\","
                + "\"requestId\":\"" + (requestId == null ? "n/a" : requestId) + "\"}}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    static String clientKey(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
