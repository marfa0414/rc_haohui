package com.rc.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Test-only, owner-private atomic file messages. Never archive its configuration directory. */
final class ProcessProtocol {
    static final ObjectMapper JSON = new ObjectMapper();
    private ProcessProtocol() { }
    static void privateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    static void privateFile(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    static void write(Path destination, JsonNode value) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".message-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, JSON.writeValueAsString(value));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static JsonNode read(Path path) throws IOException {
        if (Files.size(path) > 1_048_576) throw new IOException("Process control message exceeds its bounded size");
        return JSON.readTree(Files.readString(path));
    }
}
