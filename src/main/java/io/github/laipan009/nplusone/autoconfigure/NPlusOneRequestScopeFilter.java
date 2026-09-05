package io.github.laipan009.nplusone.autoconfigure;

import io.github.laipan009.nplusone.core.NPlusOneDetector;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Opens a detection scope for the duration of one servlet request on the request thread.
 */
public class NPlusOneRequestScopeFilter extends OncePerRequestFilter {

    public static final String REQUEST_SCOPE = "request";

    private final NPlusOneDetector detector;

    public NPlusOneRequestScopeFilter(NPlusOneDetector detector) {
        this.detector = detector;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        detector.openScope(REQUEST_SCOPE, "HTTP " + request.getMethod() + " " + request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            detector.closeScope(REQUEST_SCOPE);
        }
    }
}
