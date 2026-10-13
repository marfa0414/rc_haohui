package com.rc.notification.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP04 TC-MAP-06/07 wire fidelity regression: decoded server strings cannot prove UTF-8 Header bytes. */
@Timeout(15)
class OutboundWireEncodingTest {
    @Test
    void unicodeOrdinaryHeaderIsWrittenAsExactUtf8RatherThanSilentlyReplaced() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var client = new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, 1)) {
            server.setSoTimeout(5000);
            var captured = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var header = new ByteArrayOutputStream();
                    int matched = 0;
                    byte[] end = {'\r', '\n', '\r', '\n'};
                    while (header.size() < 20000 && matched < end.length) {
                        int next = socket.getInputStream().read();
                        if (next < 0) throw new java.io.EOFException("Headers ended before CRLFCRLF");
                        header.write(next);
                        matched = next == end[matched] ? matched + 1 : next == '\r' ? 1 : 0;
                    }
                    if (matched != end.length) throw new java.io.IOException("Header capture exceeded bounded buffer");
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return header.toByteArray();
                }
            });
            String value = "中文-😀-é";
            var response = client.execute(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/wire"),
                    "POST", Map.of("X-Unicode", value), new byte[0], Duration.ofSeconds(3));
            assertEquals(200, response.statusCode());
            byte[] raw = captured.get(5, TimeUnit.SECONDS);
            byte[] expected = ("X-Unicode: " + value + "\r\n").getBytes(StandardCharsets.UTF_8);
            int offset = indexOf(raw, "X-Unicode: ".getBytes(StandardCharsets.US_ASCII));
            assertTrue(offset >= 0, "Expected ordinary header line must exist on the socket");
            assertArrayEquals(expected, java.util.Arrays.copyOfRange(raw, offset, offset + expected.length));
        } finally { executor.shutdownNow(); }
    }

    private static int indexOf(byte[] bytes, byte[] candidate) {
        outer: for (int i = 0; i <= bytes.length - candidate.length; i++) {
            for (int j = 0; j < candidate.length; j++) if (bytes[i + j] != candidate[j]) continue outer;
            return i;
        }
        return -1;
    }
}
