package com.example.ordersystem.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class JwtAuthFilterTest {
    @Test
    void downstreamFailureIsNotRewrittenAsUnauthorized() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new JwtAuthFilter(org.mockito.Mockito.mock(JwtTokenProvider.class)).doFilter(new MockHttpServletRequest(), response,
                (request, result) -> { throw new ServletException("database unavailable"); }))
                .isInstanceOf(ServletException.class).hasMessage("database unavailable");
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    void malformedAuthorizationStopsTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new JwtAuthFilter(org.mockito.Mockito.mock(JwtTokenProvider.class)).doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(called).isFalse();
    }
}
