package com.rc.notification.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** TC-API-03 / TC-IDEM-03: protocol golden vector and bounded stream consumption. */
class SubmissionParserTest {
    @Test
    void canonicalGoldenVectorSortsByUnicodeCodePointRatherThanUtf16CodeUnit() throws Exception {
        var document = new ObjectMapper().readTree("{\"\uD800\uDC00\":1,\"\uE000\":2}");
        // The specified typed stream is {s3:<U+E000>n2;s4:<U+10000>n1;} in UTF-8.
        // Its SHA-256 was independently calculated; this catches Java natural-order UTF-16 sorting.
        assertEquals("a2306156ed42f8ed15849e719357d179ba5d0b7dc0bb92c0941cfa1e777e9484",
                HexFormat.of().formatHex(new CanonicalDigest().digest(document)));
    }

    @Test
    void oversizedStreamStopsAtTheLimitPlusOneWithoutReadingAnUnboundedBody() {
        class CountingStream extends InputStream {
            int consumed;
            @Override public int read() throws IOException {
                if (++consumed > SubmissionParser.MAX_BYTES + 1) throw new IOException("unbounded read");
                return 'x';
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (consumed + length > SubmissionParser.MAX_BYTES + 1) throw new IOException("unbounded read");
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'x');
                consumed += length;
                return length;
            }
        }
        CountingStream input = new CountingStream();
        ApiException rejected = assertThrows(ApiException.class, () -> new SubmissionParser().parse(input));
        assertEquals(413, rejected.status());
        assertEquals("PAYLOAD_TOO_LARGE", rejected.code());
        assertEquals(SubmissionParser.MAX_BYTES + 1, input.consumed);
    }
}
