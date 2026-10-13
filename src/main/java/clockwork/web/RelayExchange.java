package clockwork.web;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Typed in-memory exchange. The relay can call only the existing bounded API;
 * there is no local HTTP proxy, arbitrary URL fetch, or filesystem endpoint. */
final class RelayExchange extends HttpExchange {
    interface Sink {
        void begin(int status, Headers headers) throws IOException;
        void data(byte[] bytes, int offset, int count) throws IOException;
        void end() throws IOException;
        void error();
    }
    private final Headers requestHeaders, responseHeaders = new Headers();
    private final URI uri;
    private final String method;
    private final InputStream input;
    private final Sink sink;
    private final Map<String,Object> attributes = new HashMap<>();
    private int status = -1;
    private boolean ended;
    private long remaining;
    private final OutputStream output = new OutputStream() {
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            if (ended || status == -1 || count > remaining) throw new IOException("Invalid relay response length");
            if (method.equals("HEAD") && count != 0) throw new IOException("HEAD response has no body");
            for (int current = offset; current < offset + count; current += 32768) {
                int size = Math.min(32768, offset + count - current); sink.data(bytes, current, size); remaining -= size;
            }
        }
        @Override public void close() throws IOException {
            if (ended) return;
            ended = true;
            if (remaining != 0 && !method.equals("HEAD")) { sink.error(); throw new IOException("Incomplete relay response"); }
            sink.end();
        }
    };
    RelayExchange(String method, String path, Headers headers, byte[] body, Sink sink) {
        this(method,path,headers,new ByteArrayInputStream(body),sink);
    }
    RelayExchange(String method, String path, Headers headers, InputStream body, Sink sink) {
        this.method = method; uri = URI.create(path); requestHeaders = headers; input = body; this.sink = sink;
    }
    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return method; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() { try { input.close(); if (status == -1) { ended = true; sink.error(); } else output.close(); } catch (IOException ignored) { sink.error(); } }
    @Override public InputStream getRequestBody() { return input; }
    @Override public OutputStream getResponseBody() { return output; }
    @Override public void sendResponseHeaders(int code, long length) throws IOException {
        if (status != -1 || ended) throw new IOException("Relay response already started");
        String declared = responseHeaders.getFirst("Content-Length");
        try { remaining = declared == null ? Math.max(0, length) : Long.parseLong(declared); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid relay response length"); }
        if (remaining < 0 || remaining > 8 * 1024 * 1024) throw new IOException("Relay response exceeds limit");
        responseHeaders.set("Content-Length", Long.toString(remaining));
        sink.begin(code, responseHeaders); status = code;
    }
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0); }
    @Override public int getResponseCode() { return status; }
    @Override public InetSocketAddress getLocalAddress() { return getRemoteAddress(); }
    @Override public String getProtocol() { return "relay/1"; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public void setStreams(InputStream input, OutputStream output) { throw new UnsupportedOperationException(); }
    @Override public HttpPrincipal getPrincipal() { return null; }
}
