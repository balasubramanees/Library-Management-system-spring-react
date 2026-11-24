package com.library.gateway;

import java.util.Map;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker fallback: when a downstream is failing, slow, or its circuit is open, the browser
 * gets a fast, well-formed 503 in the usual error envelope instead of a hang or an HTML error page.
 * The frontends' own circuit breaker reacts to these 503s.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        String requestId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        log.warn("[{}] Fallback for {}: {}", requestId, service, cause == null ? "unknown" : cause.toString());
        Map<String, Object> error = Map.of(
                "code", "SERVICE_UNAVAILABLE",
                "message", "The " + service.replace('-', ' ') + " is temporarily unavailable. Please try again in a few seconds.",
                "requestId", requestId == null ? "n/a" : requestId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(Map.of("error", error));
    }
}
