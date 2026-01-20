package com.treblle.bug;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Simple proxy servlet that proxies to SOAP service.
 *
 * This servlet:
 * 1. Makes a SOAP request to calculator service
 * 2. Sets Content-Length header
 * 3. Writes response data via getOutputStream()
 */
public class ProxyServlet extends HttpServlet {

    // Use environment variable or default
    private static final String BACKEND_URL = System.getenv("BACKEND_URL") != null ?
        System.getenv("BACKEND_URL") :
        "http://www.dneonline.com/calculator.asmx";

    private static final String SOAP_REQUEST =
        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tem=\"http://tempuri.org/\">\n" +
        "   <soapenv:Header/>\n" +
        "   <soapenv:Body>\n" +
        "      <tem:Add>\n" +
        "         <tem:intA>1</tem:intA>\n" +
        "         <tem:intB>2</tem:intB>\n" +
        "      </tem:Add>\n" +
        "   </soapenv:Body>\n" +
        "</soapenv:Envelope>";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Async processing with latch
        // Servlet blocks until async completes
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        System.out.println("[ProxyServlet] Starting async thread");

        // Simulate async processing in separate thread
        new Thread(() -> {
            try {
                // Make SOAP request to backend
                HttpClient client = HttpClients.createDefault();
                HttpPost request = new HttpPost(BACKEND_URL);

                // Set SOAP headers
                request.setHeader("Content-Type", "text/xml;charset=UTF-8");
                request.setHeader("SOAPAction", "http://tempuri.org/Add");

                // Set SOAP body
                request.setEntity(new StringEntity(SOAP_REQUEST));

                // Execute request
                HttpResponse backendResponse = client.execute(request);
                byte[] responseData = EntityUtils.toByteArray(backendResponse.getEntity());

                String contentType = "text/xml; charset=utf-8";
                if (backendResponse.getFirstHeader("Content-Type") != null) {
                    contentType = backendResponse.getFirstHeader("Content-Type").getValue();
                }

                System.out.println("[Async-Thread] Proxied to SOAP backend, got " + responseData.length + " bytes");

                // Set headers
                resp.setContentType(contentType);
                resp.setContentLength(responseData.length);
                resp.setStatus(HttpServletResponse.SC_OK);

                // Write data
                OutputStream out = resp.getOutputStream();
                out.write(responseData);

                resp.flushBuffer();

                System.out.println("[Async-Thread] Called flushBuffer");

            } catch (Exception e) {
                System.err.println("[Async-Thread] Error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Signal completion
                latch.countDown();
                System.out.println("[Async-Thread] Counted down latch - servlet will now return");
            }
        }).start();

        // block with latch.await()
        try {
            System.out.println("[ProxyServlet] Waiting for async completion...");
            latch.await();
            System.out.println("[ProxyServlet] Async completed, returning from servlet");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now servlet returns and filter's finally block executes
        // Stream was closed in async thread, so Treblle's copyBodyToResponse() fails
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // Reuse GET logic for POST
        doGet(req, resp);
    }
}
