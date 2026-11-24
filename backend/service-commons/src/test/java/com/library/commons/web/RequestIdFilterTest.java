package com.library.commons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void propagatesValidIncomingId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc-123");
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(seenInMdc.get()).isEqualTo("abc-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC is cleaned up").isNull();
    }

    @Test
    void replacesMalformedIdToPreventLogInjection() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "evil\nFAKE LOG LINE");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).doesNotContain("\n").hasSize(36);
    }
}
