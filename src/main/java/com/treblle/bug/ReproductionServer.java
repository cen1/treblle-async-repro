package com.treblle.bug;

import com.treblle.javax.TreblleServletFilter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.DispatcherType;
import java.util.EnumSet;

/**
 * Minimal server to reproduce Treblle SDK issue with proxy/streaming scenarios.
 */
public class ReproductionServer {

    public static void main(String[] args) throws Exception {
        Server server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Add Treblle filter
        // Set TREBLLE_API_KEY and TREBLLE_SDK_TOKEN environment variables, or use dummy values
        String apiKey = System.getenv("TREBLLE_API_KEY");
        String sdkToken = System.getenv("TREBLLE_SDK_TOKEN");

        if (apiKey == null) apiKey = "dummy-api-key";
        if (sdkToken == null) sdkToken = "dummy-sdk-token";

        // Use original Treblle SDK filter
        FilterHolder treblleFilter = new FilterHolder(TreblleServletFilter.class);
        treblleFilter.setInitParameter("apiKey", apiKey);
        treblleFilter.setInitParameter("sdkToken", sdkToken);
        //treblleFilter.setAsyncSupported(true);
        context.addFilter(treblleFilter, "/*", EnumSet.of(DispatcherType.REQUEST));
        System.out.println("Treblle SDK filter registered");

        // Add proxy servlet with async support
        ServletHolder proxyServlet = new ServletHolder(new ProxyServlet());
        proxyServlet.setAsyncSupported(true);
        context.addServlet(proxyServlet, "/*");

        server.setHandler(context);
        server.start();

        System.out.println("Server running on http://localhost:8080");

        server.join();
    }
}
