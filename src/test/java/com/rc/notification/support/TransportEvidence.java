package com.rc.notification.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Only aggregate counts, timing and booleans belong here; never wire Body, headers, keys or tokens. */
public final class TransportEvidence {
    private TransportEvidence() { }
    public static void write(String scenario, Map<String, ?> aggregate) throws IOException {
        if (!scenario.matches("cp08-[a-z0-9-]+")) throw new IllegalArgumentException("Invalid evidence name");
        Path directory = Path.of("target", "evidence"); Files.createDirectories(directory);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario + ".json").toFile(), aggregate);
    }
}
