package com.azt.streaming.shared.web;

import com.azt.streaming.shared.config.StreamingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests that name a host this service did not agree to answer to, and cross-origin
 * writes.
 *
 * <p>Both checks exist because this service binds to loopback and that is weaker than it sounds. The
 * browser is the attacker worth planning for here: it runs code from every page its owner visits,
 * and it is already inside.
 *
 * <h2>The Host check</h2>
 *
 * <p>DNS rebinding. A page resolves its own name to an address it controls, waits for the browser to
 * cache the origin, then rebinds the name to {@code 127.0.0.1}. The browser now considers that page
 * same-origin with whatever is listening there, and the same-origin policy — the thing that was
 * supposed to be protecting this — hands the page a free pass. CORS never enters into it.
 *
 * <p>What the attacker cannot change is the {@code Host} header, because it carries the name they
 * had to use to pull the trick off. Comparing it against a list of names we chose is therefore the
 * whole defence, and it is why the list is configuration rather than something inferred: an inferred
 * list would include whatever the request claimed. This is the fix Transmission's RPC and, more
 * recently, the MCP TypeScript SDK both needed.
 *
 * <h2>The Origin check</h2>
 *
 * <p>CORS does not stop a request from happening. A form-encoded {@code POST} is a "simple" request:
 * the browser sends it, runs the side effect, and only then refuses to show the response to the
 * page. For an API whose side effects are "download this torrent" and "delete this video", being
 * refused the response is no consolation, so state-changing methods check {@code Origin} directly.
 *
 * <p>A missing {@code Origin} is allowed, and that is the point of the whole design rather than a
 * gap in it. Browsers attach it to exactly the cross-origin requests worth stopping; {@code curl},
 * the smoke test and every other local tool send nothing, and keeping them unimpeded is what makes
 * this free on the inside.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AllowedHostFilter extends OncePerRequestFilter {

    /**
     * Not in {@code ProblemTypes}, where its siblings live, and the reason is structural: an
     * ArchUnit rule keeps everything out of {@code shared.error}, and a filter cannot use that
     * package's machinery anyway. A filter runs before Spring MVC, so no {@code @RestControllerAdvice}
     * will ever see what it throws — the document has to be written here, by hand.
     */
    private static final String PROBLEM_TYPE = "https://aztcast.dev/problems/host-not-allowed";

    private static final Set<HttpMethod> STATE_CHANGING =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH);

    private final List<String> allowedHosts;

    public AllowedHostFilter(StreamingProperties properties) {
        this.allowedHosts = properties.web().allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * An empty list disables both checks, on the same terms as an empty {@code allowed-origins}
     * disables CORS: the shipped default is restrictive, and turning it off is a decision someone
     * makes in one obvious place rather than a state the service can drift into.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return allowedHosts.isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String host = hostHeaderOf(request);
        if (!isAllowed(host)) {
            log.warn("Refused a request for Host '{}' — not in allowed-hosts {}", host, allowedHosts);
            refuse(response, "This service does not answer to the host '%s'.".formatted(host));
            return;
        }

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && isStateChanging(request) && !isAllowed(hostOf(origin))) {
            log.warn("Refused a {} from origin '{}'", request.getMethod(), origin);
            refuse(response, "Cross-origin writes from '%s' are not accepted.".formatted(origin));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * The name in the {@code Host} header, without its port.
     *
     * <p>Read raw rather than through {@code getServerName()}, and this is load-bearing rather than
     * fussy. {@code ForwardedHeaderFilter} — on under the {@code docker} profile, so that the rate
     * limiter can see real client addresses — rewrites {@code getServerName()} from
     * {@code X-Forwarded-Host}, and it is ordered ahead of this filter. Trusting the parsed value
     * therefore let a caller name any host it liked simply by adding a header, which is precisely
     * the check this filter exists to make unforgeable. The header a proxy set is the proxy's claim;
     * the {@code Host} line is the request's own.
     *
     * <p>The cost is parsing it here: strip the port, and keep an IPv6 literal in the brackets the
     * allowlist writes it with. Falls back to {@code getServerName()} only when there is no header
     * at all, which is HTTP/1.0 and answers with the address the connector is bound to.
     */
    private static String hostHeaderOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.HOST);
        if (header == null || header.isBlank()) {
            return request.getServerName();
        }
        String value = header.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close < 0 ? null : value.substring(0, close + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private boolean isAllowed(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isStateChanging(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return STATE_CHANGING.contains(method);
    }

    /**
     * The host an {@code Origin} names, or null if it does not name one.
     *
     * <p>Null covers the literal {@code "null"} origin a sandboxed iframe or a {@code data:} URL
     * sends, which is unparseable on purpose and must not be treated as ours.
     */
    private static String hostOf(String origin) {
        try {
            return new URI(origin).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 403 with a problem document, written directly.
     *
     * <p>{@code no-store} because a cached refusal would outlive the configuration change that fixes
     * it, and the person who just added their hostname to the allowlist would be told it is still
     * wrong.
     */
    private static void refuse(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter()
                .write(
                        """
                        {"type":"%s","title":"Host not allowed","status":403,"detail":"%s"}"""
                                .formatted(PROBLEM_TYPE, detail.replace("\\", "\\\\").replace("\"", "\\\"")));
    }
}
