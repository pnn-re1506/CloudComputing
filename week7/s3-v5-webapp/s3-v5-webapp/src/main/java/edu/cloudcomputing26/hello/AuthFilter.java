package edu.cloudcomputing26.hello;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Centralizes the per-request token check for every S3-backed endpoint.
 *
 * Unchanged from s3-v4-webapp at this exact call site: still one line,
 * "if (!AuthConfig.isAuthorized(request)) { ... }". What changed is
 * entirely inside AuthConfig.isAuthorized — it no longer compares
 * against a local Java constant, it invokes the "CheckTokenIsValid"
 * Lambda function and trusts whatever it answers. This filter has no
 * idea that happened, and doesn't need to: it still runs before any
 * matching servlet and can short-circuit the request entirely by
 * simply not calling chain.doFilter(...). The urlPatterns below match
 * every S3-backed endpoint but deliberately exclude /login, which must
 * stay reachable without a token (that's the one request that checks
 * a token in the first place).
 */
@WebFilter(filterName = "AuthFilter", urlPatterns = {"/s3/*"})
public class AuthFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        if (!AuthConfig.isAuthorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().println("Missing or incorrect token.");
            return;
        }

        chain.doFilter(request, response);
    }
}
