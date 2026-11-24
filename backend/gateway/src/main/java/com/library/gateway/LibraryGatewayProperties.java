package com.library.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalised gateway settings (see application.yml). */
@ConfigurationProperties(prefix = "library.gateway")
public record LibraryGatewayProperties(
        String userServiceUrl,
        String libraryServiceUrl,
        Resilience resilience,
        RateLimit rateLimit) {

    public record Resilience(
            Duration timeout,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int retriesForGet) {}

    public record RateLimit(int capacity, double refillPerSecond) {}
}
