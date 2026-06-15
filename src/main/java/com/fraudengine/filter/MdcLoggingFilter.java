package com.fraudengine.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Populates MDC for every inbound HTTP request so all log lines within a
 * request share the same requestId. Extracts transactionId from the path when
 * present so read-path logs are traceable to a specific transaction without
 * additional instrumentation.
 *
 * MDC is always cleared in the finally block — downstream filters and
 * controllers must not rely on MDC surviving beyond the current request.
 */
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final Pattern TRANSACTION_ID_PATH =
            Pattern.compile("/api/v1/transactions/([0-9a-fA-F\\-]{36})");

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            MDC.put("requestId", UUID.randomUUID().toString());
            MDC.put("httpMethod", request.getMethod());
            MDC.put("httpPath", request.getRequestURI());

            Matcher matcher = TRANSACTION_ID_PATH.matcher(request.getRequestURI());
            if (matcher.find()) {
                MDC.put("transactionId", matcher.group(1));
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
