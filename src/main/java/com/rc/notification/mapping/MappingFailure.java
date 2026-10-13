package com.rc.notification.mapping;

/** Carries only static diagnostic text, never a mapped value or credential. */
public final class MappingFailure extends RuntimeException {
    public enum Kind { CONFIGURATION, INPUT, OUTPUT_LIMIT }
    private final Kind kind;
    private MappingFailure(Kind kind, String safeMessage) { super(safeMessage); this.kind = kind; }
    public Kind kind() { return kind; }
    public static MappingFailure configuration(String section) {
        return new MappingFailure(Kind.CONFIGURATION, "Invalid configuration at " + section);
    }
    public static MappingFailure input() {
        return new MappingFailure(Kind.INPUT, "Business data does not satisfy the field mapping");
    }
    public static MappingFailure limit() {
        return new MappingFailure(Kind.OUTPUT_LIMIT, "Rendered request exceeds the byte limit");
    }
}
