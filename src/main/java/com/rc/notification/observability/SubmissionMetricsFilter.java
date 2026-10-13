package com.rc.notification.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Observe the fixed submission route, including authentication failures before MVC. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class SubmissionMetricsFilter extends OncePerRequestFilter {
    private final NotificationMetrics metrics;
    public SubmissionMetricsFilter(NotificationMetrics metrics) { this.metrics = metrics; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/v1/notifications").equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean completed = false;
        try { chain.doFilter(request, response); completed = true; }
        finally { metrics.response(completed ? response.getStatus() : 500, System.nanoTime() - started); }
    }
}
