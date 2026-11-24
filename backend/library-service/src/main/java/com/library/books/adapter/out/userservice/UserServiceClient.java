package com.library.books.adapter.out.userservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.library.books.application.port.MemberDirectory;
import com.library.books.domain.Member;
import com.library.commons.web.RequestIdFilter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * Adapter for the {@link MemberDirectory} port: token introspection against user-service.
 *
 * <p>Resilience (configured in {@code application.yml}, instance {@value #RESILIENCE_ID}):
 * <ul>
 *   <li>connect / read timeouts on the HTTP client, so a hung user-service cannot pin our threads;</li>
 *   <li>{@code @Retry}: a couple of quick retries with backoff - safe because introspection is a read;</li>
 *   <li>{@code @CircuitBreaker}: after repeated failures we stop calling user-service for a while and
 *       fail fast with 503, giving it room to recover instead of hammering it.</li>
 * </ul>
 * An invalid token (401) is a normal answer, not a failure, and never trips the breaker.
 */
public class UserServiceClient implements MemberDirectory {

    static final String RESILIENCE_ID = "userService";

    private final RestClient restClient;

    public UserServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = RESILIENCE_ID)
    @CircuitBreaker(name = RESILIENCE_ID)
    public Optional<Member> findByToken(String bearerToken) {
        try {
            return restClient.get()
                    .uri("/sessions/current")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(RequestIdFilter.HEADER, RequestIdFilter.currentRequestId())
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == HttpStatus.OK) {
                            UserPayload user = response.bodyTo(UserPayload.class);
                            if (user == null) {
                                throw new UserServiceUnavailableException("user-service returned an empty body");
                            }
                            return Optional.of(new Member(user.id(), user.name(), user.isAdmin()));
                        }
                        if (status == HttpStatus.UNAUTHORIZED) {
                            return Optional.<Member>empty();
                        }
                        throw new UserServiceUnavailableException("user-service answered " + response.getStatusCode());
                    });
        } catch (RestClientException e) {
            // I/O errors and timeouts (ResourceAccessException), unreadable bodies, ...
            throw new UserServiceUnavailableException("user-service is unreachable", e);
        }
    }

    record UserPayload(String id, String name, @JsonProperty("isAdmin") boolean isAdmin) {}
}
