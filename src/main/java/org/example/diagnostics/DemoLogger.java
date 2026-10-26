package org.example.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Console output plus a persistent record of actual decisions from this run. */
public final class DemoLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("T48");
    private final Path path = Path.of("logs", "demo.log");

    public DemoLogger() throws IOException {
        Files.createDirectories(path.getParent());
    }

    public synchronized void log(String message) {
        LOGGER.info(message);
        try {
            Files.writeString(path, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            LOGGER.error("Cannot append demo log", exception);
        }
    }
}
