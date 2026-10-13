package com.rc.notification.delivery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.function.Predicate;
import org.apache.hc.client5.http.DnsResolver;

/** Validates exactly the addresses returned to the connection manager, with no second lookup. */
public final class ValidatedDnsResolver implements DnsResolver {
    private final DnsResolver delegate;
    private final Predicate<InetAddress> allowedAddress;

    public ValidatedDnsResolver(DnsResolver delegate, Predicate<InetAddress> allowedAddress) {
        this.delegate = Objects.requireNonNull(delegate);
        this.allowedAddress = Objects.requireNonNull(allowedAddress);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("Target did not resolve to an address");
        }
        for (InetAddress address : addresses) {
            if (address == null || !allowedAddress.test(address)) {
                throw new TargetAddressRejectedException();
            }
        }
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Keep the original hostname for Host/SNI. Canonicalization must not introduce another lookup.
        return host;
    }
}
