package com.library.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.Set;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

/**
 * Routing table plus per-route resilience.
 *
 * <pre>
 *   /api/sessions/**          -> user-service    /sessions/**
 *   /api/books/**, /api/me/**, /api/admin/** -> library-service /books/**, /me/**, /admin/**
 * </pre>
 *
 * Each route is wrapped in its own circuit breaker (one per downstream, so a failing user-service
 * does not stop catalogue reads from being attempted) with a time limiter and a JSON fallback.
 * Only idempotent GETs are retried: retrying a POST such as "borrow" after a timeout could apply it twice.
 */
@Configuration
public class RoutesConfig {

    static final Set<String> FAILURE_STATUS_CODES = Set.of("500", "502", "503", "504");

    private final LibraryGatewayProperties properties;

    public RoutesConfig(LibraryGatewayProperties properties) {
        this.properties = properties;
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("user-service", route -> route
                        .path("/api/sessions", "/api/sessions/**")
                        .filters(filters -> resilient(filters, "userService", "user-service"))
                        .uri(properties.userServiceUrl()))
                .route("library-service", route -> route
                        .path("/api/books", "/api/books/**", "/api/me", "/api/me/**", "/api/admin/**")
                        .filters(filters -> resilient(filters, "libraryService", "library-service"))
                        .uri(properties.libraryServiceUrl()))
                .build();
    }

    private GatewayFilterSpec resilient(GatewayFilterSpec filters, String breakerName, String serviceName) {
        return filters
                .stripPrefix(1)
                .setRequestSize(DataSize.ofKilobytes(16))
                .circuitBreaker(config -> {
                    config.setName(breakerName);
                    config.setFallbackUri("forward:/fallback/" + serviceName);
                    config.setStatusCodes(FAILURE_STATUS_CODES);
                })
                .retry(config -> {
                    config.setRetries(properties.resilience().retriesForGet());
                    config.setMethods(HttpMethod.GET);
                    config.setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT);
                    config.setBackoff(Duration.ofMillis(50), Duration.ofMillis(500), 2, true);
                });
    }

    /** Defaults for every gateway circuit breaker. */
    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults() {
        LibraryGatewayProperties.Resilience r = properties.resilience();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(r.slidingWindowSize())
                        .minimumNumberOfCalls(r.minimumNumberOfCalls())
                        .failureRateThreshold(r.failureRateThreshold())
                        .waitDurationInOpenState(r.waitDurationInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(r.timeout()).build())
                .build());
    }
}
