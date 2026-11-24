package com.library.books.config;

import com.library.books.adapter.out.clock.AdjustableClock;
import com.library.books.adapter.out.memory.InMemoryAccountRepository;
import com.library.books.adapter.out.memory.InMemoryBookRepository;
import com.library.books.adapter.out.memory.InMemoryNotificationInbox;
import com.library.books.adapter.out.userservice.UserServiceClient;
import com.library.books.application.LibraryService;
import com.library.books.application.port.AccountRepository;
import com.library.books.application.port.BookRepository;
import com.library.books.application.port.MemberDirectory;
import com.library.books.application.port.NotificationInbox;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composition root: wires adapters to ports. Domain and application classes stay framework-free. */
@Configuration
@EnableConfigurationProperties(LendingProperties.class)
public class LibraryServiceConfig {

    /** The library's clock, in its own time zone (due dates are calendar dates there). */
    @Bean
    AdjustableClock libraryClock(@Value("${library.time-zone:Asia/Kolkata}") String timeZone) {
        return new AdjustableClock(Clock.system(ZoneId.of(timeZone)));
    }

    @Bean
    BookRepository bookRepository() {
        return new InMemoryBookRepository();
    }

    @Bean
    AccountRepository accountRepository() {
        return new InMemoryAccountRepository();
    }

    @Bean
    NotificationInbox notificationInbox() {
        return new InMemoryNotificationInbox();
    }

    @Bean
    LibraryService libraryService(BookRepository books, AccountRepository accounts, NotificationInbox inbox,
                                  AdjustableClock clock, LendingProperties lending) {
        return new LibraryService(books, accounts, inbox, () -> UUID.randomUUID().toString(), clock, lending.toPolicy());
    }

    @Bean
    MemberDirectory memberDirectory(
            @Value("${library.user-service.base-url}") String baseUrl,
            @Value("${library.user-service.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${library.user-service.read-timeout:PT2S}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        return new UserServiceClient(restClient);
    }
}
