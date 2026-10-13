package com.rc.notification.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestWriter;
import org.apache.hc.core5.http.io.HttpTransportMetrics;
import org.apache.hc.core5.http.io.SessionOutputBuffer;
import org.apache.hc.core5.util.CharArrayBuffer;

/**
 * Keeps the library's HTTP framing, with explicit UTF-8 request header bytes.
 * Configuring the connection charset would also change response decoding and reject valid opaque
 * response-header octets. Only the request writer needs this encoding contract.
 */
final class Utf8RequestWriter extends DefaultHttpRequestWriter {
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

    @Override
    public void write(ClassicHttpRequest message, SessionOutputBuffer buffer, OutputStream stream)
            throws IOException, HttpException {
        super.write(message, new Utf8Lines(buffer, encoder), stream);
    }

    private record Utf8Lines(SessionOutputBuffer delegate, CharsetEncoder encoder) implements SessionOutputBuffer {
        private static final byte[] CRLF = {'\r', '\n'};

        @Override public int length() { return delegate.length(); }
        @Override public int capacity() { return delegate.capacity(); }
        @Override public int available() { return delegate.available(); }
        @Override public HttpTransportMetrics getMetrics() { return delegate.getMetrics(); }
        @Override public void flush(OutputStream stream) throws IOException { delegate.flush(stream); }
        @Override public void write(int value, OutputStream stream) throws IOException { delegate.write(value, stream); }
        @Override public void write(byte[] bytes, OutputStream stream) throws IOException { delegate.write(bytes, stream); }
        @Override public void write(byte[] bytes, int offset, int length, OutputStream stream) throws IOException {
            delegate.write(bytes, offset, length, stream);
        }
        @Override public void writeLine(CharArrayBuffer line, OutputStream stream) throws IOException {
            if (line == null) return;
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(line.array(), 0, line.length()));
            delegate.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining(), stream);
            delegate.write(CRLF, stream);
        }
    }
}
