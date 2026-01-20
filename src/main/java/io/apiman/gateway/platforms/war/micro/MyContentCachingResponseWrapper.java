package io.apiman.gateway.platforms.war.micro;

import com.treblle.javax.infrastructure.ContentCachingResponseWrapper;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Instrumented version of ContentCachingResponseWrapper with extensive logging
 * to debug why the EOF exception isn't being triggered in reproduction.
 */
public class MyContentCachingResponseWrapper extends ContentCachingResponseWrapper {

    private static int instanceCounter = 0;
    private final int instanceId;

    public MyContentCachingResponseWrapper(HttpServletResponse response) {
        super(response);
        this.instanceId = ++instanceCounter;
        System.out.println("[MyWrapper-" + instanceId + "] Created wrapper for response: " + response.getClass().getName());
    }

    public MyContentCachingResponseWrapper(HttpServletResponse response, int contentCacheLimit) {
        super(response, contentCacheLimit);
        this.instanceId = ++instanceCounter;
        System.out.println("[MyWrapper-" + instanceId + "] Created wrapper with cache limit: " + contentCacheLimit);
    }

    @Override
    protected void copyBodyToResponse(boolean complete) throws IOException {
        System.out.println("\n========== [MyWrapper-" + instanceId + "] copyBodyToResponse START ==========");
        System.out.println("[MyWrapper-" + instanceId + "] complete=" + complete);
        System.out.println("[MyWrapper-" + instanceId + "] contentSize=" + this.getContentSize());

        HttpServletResponse rawResponse = (HttpServletResponse) this.getResponse();
        System.out.println("[MyWrapper-" + instanceId + "] rawResponse.isCommitted()=" + rawResponse.isCommitted());
        System.out.println("[MyWrapper-" + instanceId + "] rawResponse class=" + rawResponse.getClass().getName());

        if (this.getContentSize() == 0) {
            System.out.println("[MyWrapper-" + instanceId + "] No content to copy, returning early");
            System.out.println("========== [MyWrapper-" + instanceId + "] copyBodyToResponse END ==========\n");
            return;
        }

        try {
            System.out.println("[MyWrapper-" + instanceId + "] Attempting to write " + this.getContentSize() + " bytes to response");

            // Try to set headers if not committed
            if (!rawResponse.isCommitted()) {
                System.out.println("[MyWrapper-" + instanceId + "] Response not committed, setting headers");
                if (complete || this.getHeader("Content-Length") != null) {
                    if (rawResponse.getHeader("Transfer-Encoding") == null) {
                        System.out.println("[MyWrapper-" + instanceId + "] Setting Content-Length: " + this.getContentSize());
                        rawResponse.setContentLength(this.getContentSize());
                    }
                }
                if (complete || this.getContentType() != null) {
                    System.out.println("[MyWrapper-" + instanceId + "] Setting Content-Type: " + this.getContentType());
                    rawResponse.setContentType(this.getContentType());
                }
            } else {
                System.out.println("[MyWrapper-" + instanceId + "] Response ALREADY COMMITTED, skipping header setting");
            }

            // Try to write the cached content
            byte[] content = this.getContentAsByteArray();
            System.out.println("[MyWrapper-" + instanceId + "] Got content array, length=" + content.length);
            System.out.println("[MyWrapper-" + instanceId + "] About to call rawResponse.getOutputStream()");

            try {
                javax.servlet.ServletOutputStream outputStream = rawResponse.getOutputStream();
                System.out.println("[MyWrapper-" + instanceId + "] Got output stream: " + outputStream.getClass().getName());
                System.out.println("[MyWrapper-" + instanceId + "] About to write " + content.length + " bytes...");

                outputStream.write(content);
                System.out.println("[MyWrapper-" + instanceId + "] Write successful!");

                if (complete) {
                    System.out.println("[MyWrapper-" + instanceId + "] Flushing buffer...");
                    rawResponse.flushBuffer();
                    System.out.println("[MyWrapper-" + instanceId + "] Flush successful!");
                }
            } catch (IOException writeException) {
                System.err.println("\n!!! [MyWrapper-" + instanceId + "] IOException during write !!!");
                System.err.println("[MyWrapper-" + instanceId + "] Exception type: " + writeException.getClass().getName());
                System.err.println("[MyWrapper-" + instanceId + "] Exception message: " + writeException.getMessage());
                writeException.printStackTrace(System.err);
                throw writeException;  // Re-throw to preserve original behavior
            }

        } catch (IOException e) {
            System.err.println("\n!!! [MyWrapper-" + instanceId + "] CAUGHT IOException in copyBodyToResponse !!!");
            System.err.println("[MyWrapper-" + instanceId + "] Exception: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);

            // In production, this exception is caught and logged by TreblleServletFilter
            // The client already has the response data (was written during filter chain)
            // The cached content is still available for Treblle analytics
            System.err.println("[MyWrapper-" + instanceId + "] This is the BUG - trying to write to closed stream!");

            throw e;  // Re-throw for original behavior
        } finally {
            System.out.println("========== [MyWrapper-" + instanceId + "] copyBodyToResponse END ==========\n");
        }
    }
}
