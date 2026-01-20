package io.apiman.gateway.platforms.war.micro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.treblle.common.dto.TrebllePayload;
import com.treblle.common.service.TreblleService;
import com.treblle.common.utils.PathMatcher;
import com.treblle.javax.configuration.ServletFilterTreblleProperties;
import com.treblle.javax.infrastructure.ContentCachingRequestWrapper;
import com.treblle.javax.service.TreblleServiceImpl;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Instrumented version of TreblleServletFilter with extensive logging
 * to debug why the EOF exception isn't being triggered in reproduction.
 */
public class MyTreblleServletFilter implements Filter {
    private static final String SDK_NAME = "javax-servlet";
    private TreblleService treblleService;
    private static int requestCounter = 0;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        System.out.println("\n========== MyTreblleServletFilter INIT ==========");
        try {
            this.treblleService = new TreblleServiceImpl(SDK_NAME, new ServletFilterTreblleProperties(filterConfig), new ObjectMapper());
            System.out.println("[MyTreblleFilter] Initialized successfully");
        } catch (IllegalStateException e) {
            System.err.println("[MyTreblleFilter] Initialization FAILED: " + e.getMessage());
            throw new ServletException("Failed to initialize Treblle SDK: " + e.getMessage(), e);
        }
        System.out.println("========== MyTreblleServletFilter INIT END ==========\n");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        int requestId = ++requestCounter;
        System.out.println("\n========================================");
        System.out.println("==== MyTreblleFilter Request #" + requestId + " START ====");
        System.out.println("========================================");

        int maxBodySize = this.treblleService.getMaxBodySizeInBytes();
        System.out.println("[MyFilter-" + requestId + "] Creating request wrapper with maxBodySize=" + maxBodySize);
        ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper((HttpServletRequest) request, maxBodySize);

        System.out.println("[MyFilter-" + requestId + "] Creating MyContentCachingResponseWrapper");
        MyContentCachingResponseWrapper cachingResponse = new MyContentCachingResponseWrapper((HttpServletResponse) response, maxBodySize);

        String requestPath = this.extractRequestPath(cachingRequest);
        System.out.println("[MyFilter-" + requestId + "] Request path: " + requestPath);

        List<String> excludedPaths = this.treblleService.getProperties().getExcludedPaths();
        if (PathMatcher.isExcluded(requestPath, excludedPaths)) {
            System.out.println("[MyFilter-" + requestId + "] Path excluded, passing through without monitoring");
            filterChain.doFilter(cachingRequest, cachingResponse);
            System.out.println("[MyFilter-" + requestId + "] Filter chain returned (excluded path)");
            return;
        }

        long start = System.currentTimeMillis();
        Exception potentialException = null;

        try {
            System.out.println("[MyFilter-" + requestId + "] About to call filterChain.doFilter()");
            filterChain.doFilter(cachingRequest, cachingResponse);
            System.out.println("[MyFilter-" + requestId + "] filterChain.doFilter() returned successfully");
        } catch (Exception exception) {
            System.err.println("[MyFilter-" + requestId + "] Exception during filter chain: " + exception.getClass().getName());
            System.err.println("[MyFilter-" + requestId + "] Exception message: " + exception.getMessage());
            exception.printStackTrace(System.err);
            potentialException = exception;
        } finally {
            System.out.println("\n[MyFilter-" + requestId + "] ===== ENTERING FINALLY BLOCK =====");
            long responseTimeInMillis = System.currentTimeMillis() - start;
            System.out.println("[MyFilter-" + requestId + "] Response time: " + responseTimeInMillis + "ms");

            byte[] requestBody = cachingRequest.getContentAsByteArray();
            byte[] responseBody = cachingResponse.getContentAsByteArray();
            System.out.println("[MyFilter-" + requestId + "] Cached request body: " + requestBody.length + " bytes");
            System.out.println("[MyFilter-" + requestId + "] Cached response body: " + responseBody.length + " bytes");
            System.out.println("[MyFilter-" + requestId + "] Response committed: " + ((HttpServletResponse)response).isCommitted());

            boolean responseRestored = false;

            try {
                System.out.println("[MyFilter-" + requestId + "] About to call cachingResponse.copyBodyToResponse()");
                cachingResponse.copyBodyToResponse();
                responseRestored = true;
                System.out.println("[MyFilter-" + requestId + "] copyBodyToResponse() completed successfully!");
            } catch (IOException copyException) {
                System.err.println("\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                System.err.println("!!! [MyFilter-" + requestId + "] CRITICAL: Failed to restore response body to client !!!");
                System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                System.err.println("[MyFilter-" + requestId + "] Exception: " + copyException.getClass().getName());
                System.err.println("[MyFilter-" + requestId + "] Message: " + copyException.getMessage());
                copyException.printStackTrace(System.err);

                if (potentialException == null) {
                    potentialException = copyException;
                } else {
                    potentialException.addSuppressed(copyException);
                }
            }

            System.out.println("[MyFilter-" + requestId + "] Response restored: " + responseRestored);

            // Always send telemetry
            try {
                System.out.println("[MyFilter-" + requestId + "] Creating Treblle payload...");
                TrebllePayload payload = this.treblleService.createPayload(
                        cachingRequest, cachingResponse, potentialException, responseTimeInMillis);
                System.out.println("[MyFilter-" + requestId + "] Sending payload to Treblle...");
                this.treblleService.maskAndSendPayload(payload, requestBody, responseBody, potentialException);
                System.out.println("[MyFilter-" + requestId + "] Payload sent successfully");
            } catch (Exception telemetryException) {
                System.err.println("[MyFilter-" + requestId + "] Error sending data to Treblle: " + telemetryException.getMessage());
                telemetryException.printStackTrace(System.err);
            }

            System.out.println("[MyFilter-" + requestId + "] ===== EXITING FINALLY BLOCK =====\n");
        }

        if (potentialException != null) {
            System.err.println("[MyFilter-" + requestId + "] Re-throwing exception: " + potentialException.getClass().getName());
            if (potentialException instanceof IOException) {
                throw (IOException) potentialException;
            } else if (potentialException instanceof ServletException) {
                throw (ServletException) potentialException;
            } else if (potentialException instanceof RuntimeException) {
                throw (RuntimeException) potentialException;
            } else {
                throw new ServletException(potentialException);
            }
        }

        System.out.println("========================================");
        System.out.println("==== MyTreblleFilter Request #" + requestId + " END ====");
        System.out.println("========================================\n");
    }

    @Override
    public void destroy() {
        System.out.println("\n========== MyTreblleServletFilter DESTROY ==========");
        if (this.treblleService instanceof TreblleServiceImpl) {
            ((TreblleServiceImpl) this.treblleService).shutdown();
        }
        System.out.println("========== MyTreblleServletFilter DESTROY END ==========\n");
    }

    private String extractRequestPath(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && requestURI.startsWith(contextPath)
                ? requestURI.substring(contextPath.length())
                : requestURI;
    }
}
