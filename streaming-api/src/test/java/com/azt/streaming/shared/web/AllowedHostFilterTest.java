package com.azt.streaming.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AllowedHostFilterTest {

    private final AllowedHostFilter filter =
            new AllowedHostFilter(PropertiesFixture.defaults().allowedHosts("localhost", "127.0.0.1").build());

    /** Runs one request through the filter and reports what came back. */
    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    /** A request carrying a real Host line, which is the thing the filter reads. */
    private static MockHttpServletRequest request(String method, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/videos");
        request.addHeader(HttpHeaders.HOST, host);
        request.setServerName(host);
        return request;
    }

    @Test
    void passesARequestForAnAllowedHost() throws Exception {
        assertThat(run(request("GET", "localhost")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("refuses a host we never agreed to answer to — the rebinding case")
    void refusesAnUnknownHost() throws Exception {
        // The whole attack is that the browser thinks this is same-origin. Host carries the name the
        // attacker had to use to arrange that, and it is the one thing they cannot forge away.
        MockHttpServletResponse response = run(request("GET", "rebind.evil.example"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("host-not-allowed");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    @DisplayName("hosts match case-insensitively, as the DNS name they are")
    void hostMatchingIgnoresCase() throws Exception {
        assertThat(run(request("GET", "LOCALHOST")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("the port is not part of the name")
    void hostMatchingIgnoresThePort() throws Exception {
        assertThat(run(request("GET", "localhost:8000")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("an IPv6 literal keeps the brackets the allowlist writes it with")
    void matchesABracketedIpv6Literal() throws Exception {
        AllowedHostFilter six =
                new AllowedHostFilter(PropertiesFixture.defaults().allowedHosts("[::1]").build());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/videos");
        request.addHeader(HttpHeaders.HOST, "[::1]:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        six.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("X-Forwarded-Host does not get a caller past the check")
    void aForwardedHostHeaderCannotOverrideTheHostLine() throws Exception {
        // ForwardedHeaderFilter is on under the docker profile, is ordered ahead of this one, and
        // rewrites getServerName() from this header. Reading the parsed value would let any caller
        // name any host by adding a header — which is exactly the forgery this filter prevents.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/videos");
        request.addHeader(HttpHeaders.HOST, "rebind.evil.example");
        request.addHeader("X-Forwarded-Host", "localhost");
        request.setServerName("localhost"); // what ForwardedHeaderFilter would have left behind
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("refuses a cross-origin write, which CORS would have allowed to happen")
    void refusesACrossOriginWrite() throws Exception {
        // A form-encoded POST is a "simple" request: the browser sends it, the side effect lands,
        // and only the response is withheld. For "download this torrent" that is no consolation.
        MockHttpServletRequest request = request("POST", "localhost");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");

        assertThat(run(request).getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("a cross-origin read is left to CORS")
    void allowsACrossOriginRead() throws Exception {
        // Nothing changes on a GET, and refusing here would duplicate the CORS layer badly: it is
        // the one that knows which origins may *read* a response.
        MockHttpServletRequest request = request("GET", "localhost");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");

        assertThat(run(request).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("a write from our own origin is allowed")
    void allowsASameOriginWrite() throws Exception {
        MockHttpServletRequest request = request("POST", "localhost");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:8000");

        assertThat(run(request).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("a write with no Origin is allowed — that is curl, and the point")
    void allowsAWriteWithNoOrigin() throws Exception {
        // Browsers attach Origin to exactly the requests worth stopping. Everything local sends
        // nothing, and leaving those unimpeded is what makes this free on the inside.
        assertThat(run(request("POST", "localhost")).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("the opaque origin a sandboxed frame sends is not ours")
    void refusesTheNullOrigin() throws Exception {
        MockHttpServletRequest request = request("POST", "localhost");
        request.addHeader(HttpHeaders.ORIGIN, "null");

        assertThat(run(request).getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("an empty allowlist switches both checks off, like an empty allowed-origins")
    void anEmptyListDisablesTheFilter() throws Exception {
        AllowedHostFilter off = new AllowedHostFilter(PropertiesFixture.defaults().build());
        MockHttpServletRequest request = request("POST", "anything.example");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        off.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }
}
