package com.rc.notification.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.core5.net.InetAddressUtils;

/** Parses only validated numeric address syntax; never performs a hostname lookup. */
public final class IpLiteral {
    private IpLiteral() { }
    public static InetAddress parse(String value) {
        if (value == null || value.isEmpty()) throw invalid();
        String host = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        if (host.indexOf('%') >= 0) throw invalid(); // No scoped IPv6 or encoded authority tricks.
        try {
            if (host.indexOf(':') >= 0) {
                if (!InetAddressUtils.isIPv6Address(host)) throw invalid();
                return InetAddress.getByName(host); // A validated ':' literal cannot take the DNS path.
            }
            if (host.matches("[0-9.]+")) {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4) throw invalid();
                byte[] bytes = new byte[4];
                for (int index = 0; index < parts.length; index++) {
                    String part = parts[index];
                    if (!part.matches("0|[1-9][0-9]{0,2}")) throw invalid();
                    int octet = Integer.parseInt(part);
                    if (octet > 255) throw invalid();
                    bytes[index] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")) throw invalid();
            return null;
        } catch (UnknownHostException impossible) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid address literal"); }
}
