package com.cardroid.app.update;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubTransportTest {
    /** Plain java.net server, compatible with Android's restricted JVM test bootclasspath. */
    private void assertResponse(int code, String body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder headers = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    String request = headers.toString().toLowerCase(java.util.Locale.ROOT);
                    assertTrue(request.contains("accept: application/vnd.github+json"));
                    assertFalse(request.contains("authorization:"));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 " + code + " Test\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                } catch (Throwable error) { failure.set(error); }
            });
            worker.setDaemon(true); worker.start();
            UpdateChecker.Response result = new GitHubTransport(new URL("http://127.0.0.1:" + server.getLocalPort() + "/release")).fetch();
            worker.join(10000);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertFalse(worker.isAlive());
            assertEquals(code, result.code);
            assertEquals(code == 200 ? body : "", result.body);
        }
    }
    @Test public void readsSuccessfulResponseBody() throws Exception {
        assertResponse(200, "{\"tag_name\":\"v0.1.1\"}");
    }
    @Test public void fetchesJsonOverHttpAndReturnsErrorStatusWithoutParsing() throws Exception {
        assertResponse(429, "{\"message\":\"rate limited\"}");
    }
}
