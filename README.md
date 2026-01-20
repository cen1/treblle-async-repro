# Treblle SDK async Reproduction

Minimal Maven project demonstrating a possible issue in Treblle JavaX SDK (version 2.0.1) when used in async servlet handlers.

## The issue

When a servlet streams response data via `getOutputStream()`, Treblle's `ContentCachingResponseWrapper` writes data to both the client and an internal cache. 
After the servlet completes, `copyBodyToResponse()` tries to write the cached data again, but the stream is already closed.

**Result:** `org.eclipse.jetty.io.EofException: Closed` in logs.

We are not sure why any write operations are done, since the filter could be read only for the needed functionality.

## Prerequisites

- Java 11
- Maven

## How to Run

### 1. Build the Project

```bash
mvn clean package
```

### 2. Run the Server

```bash
# No need for Treblle credentials (telemetry won't be sent but issue happens before that)
mvn exec:java
```

The server will start on http://localhost:8080

### 3. Make a Test Request

In another terminal:

```bash
curl -v http://localhost:8080/test
```

## Expected vs Actual Behavior

### Expected

**Response Headers:**
```
HTTP/1.1 200 OK
Content-Type: application/json;charset=utf-8
Content-Length: 198
```

**Server Logs:**
- No errors

### Actual

**Response Headers:**
```
HTTP/1.1 200 OK
Content-Type: text/xml;charset=utf-8
Transfer-Encoding: chunked
```

**Server Logs:**
```
[ProxyServlet] Set Content-Length: 325
[ProxyServlet] Set Content-Type: text/xml;charset=utf-8
[ProxyServlet] Wrote 325 bytes to output stream
CRITICAL: Failed to restore response body to client
org.eclipse.jetty.io.EofException: Closed
    at org.eclipse.jetty.server.HttpOutput.write(HttpOutput.java:482)
    at java.io.ByteArrayOutputStream.writeTo(ByteArrayOutputStream.java:187)
    at com.treblle.javax.infrastructure.ContentCachingResponseWrapper.copyBodyToResponse(ContentCachingResponseWrapper.java:215)
    at com.treblle.javax.infrastructure.ContentCachingResponseWrapper.copyBodyToResponse(ContentCachingResponseWrapper.java:194)
    at com.treblle.javax.TreblleServletFilter.doFilter(TreblleServletFilter.java:100)
    ...
```

