package com.homefix.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void inboundHeader_isReusedAndEchoed() throws Exception {
        String inbound = "test-correlation-abc";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(inbound);
    }

    @Test
    void missingHeader_generatesUuidV4() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        // Must be a parseable UUID (version 4).
        UUID parsed = UUID.fromString(generated);
        assertThat(parsed.version()).isEqualTo(4);
    }

    @Test
    void blankInboundHeader_generatesNewId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(generated.trim()).isNotEmpty();
        UUID.fromString(generated); // parseable
    }

    @Test
    void mdcIsPopulatedDuringChainAndClearedAfter() throws Exception {
        String inbound = "mdc-check-123";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer((Answer<Void>) inv -> {
            mdcDuringChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo(inbound);
        // Cleared after the request completes to avoid thread-pool leakage.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcIsClearedEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        try {
            doAnswer(inv -> {
                throw new RuntimeException("boom");
            }).when(chain).doFilter(request, response);
            filter.doFilter(request, response, chain);
        } catch (Exception ignored) {
            // expected
        }
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void filterOrder_isHighestPrecedenceByDefault() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(new CorrelationIdFilter(42).getOrder()).isEqualTo(42);
    }
}
