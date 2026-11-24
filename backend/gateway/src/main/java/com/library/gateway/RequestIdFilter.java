package com.library.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge of the correlation-id chain: accept a well-formed {@code X-Request-Id} from the browser or
 * create one, forward it downstream, echo it to the client and write one access-log line per request.
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger accessLog = LoggerFactory.getLogger("access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && VALID_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, requestId)).build();
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponse().getHeaders().set("Cache-Control", "no-store");
        long started = System.nanoTime();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> accessLog.info("[{}] {} {} -> {} in {}ms", requestId, request.getMethod(),
                        request.getPath().value(), exchange.getResponse().getStatusCode(),
                        (System.nanoTime() - started) / 1_000_000));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
