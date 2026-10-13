package com.rc.notification.mapping;

import com.rc.notification.application.SubmissionParser;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/** Stops encoding at the limit, including when several mappings reuse a large input value. */
final class BoundedOutput extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    @Override public void write(int value) { ensureCapacity(1); bytes.write(value); }
    @Override public void write(byte[] value, int offset, int length) {
        ensureCapacity(length);
        bytes.write(value, offset, length);
    }
    private void ensureCapacity(int additional) {
        if (additional > SubmissionParser.MAX_BYTES - bytes.size()) throw MappingFailure.limit();
    }
    byte[] bytes() { return bytes.toByteArray(); }
}
