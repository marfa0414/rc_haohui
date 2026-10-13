package com.rc.notification.mapping;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** A format strategy; vendor-specific classes are unnecessary. */
public interface BodyEncoder {
    String format();
    String contentType();
    byte[] encode(ObjectNode fields);
}
