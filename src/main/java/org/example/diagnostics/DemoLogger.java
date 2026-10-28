package org.example.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Console output plus a persistent record of actual decisions from this run. */
public final class DemoLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("T48");
    private static final String RESET = "\u001B[0m";
    private final Path path = Path.of("logs", "demo.log");

    private enum Style {
        ACCEPT("\u001B[92m", Level.INFO),
        REJECT("\u001B[91m", Level.WARN),
        ROLLBACK("\u001B[93m", Level.INFO),
        ERROR("\u001B[1;91m", Level.ERROR),
        DEATH("\u001B[95m", Level.INFO),
        PACKET("\u001B[90m", Level.INFO),
        SYSTEM("\u001B[96m", Level.INFO);

        private final String color;
        private final Level level;

        Style(String color, Level level) {
            this.color = color;
            this.level = level;
        }
    }

    public DemoLogger() throws IOException {
        Files.createDirectories(path.getParent());
    }

    public synchronized void log(String message) {
        Style style = styleOf(message);
        LOGGER.atLevel(style.level).log(style.color + message + RESET);
        try {
            Files.writeString(path, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            LOGGER.error(Style.ERROR.color + "Cannot append demo log" + RESET, exception);
        }
    }

    private static Style styleOf(String message) {
        // Match event fields rather than arbitrary player names or reason text.
        if (message.startsWith("BOUNDARY_SETUP_FAILED ")
                || message.startsWith("[BOUNDARY] ROLLBACK_FAILED ")
                || message.startsWith("ERROR ")) {
            return Style.ERROR;
        }
        if (message.startsWith("[MOVEMENT][PACKET] REJECT ")
                || message.startsWith("[BOUNDARY] REJECT ")
                || (message.startsWith("ATTACK ") && message.contains(" decision=REJECT "))) {
            return Style.REJECT;
        }
        if (message.startsWith("[MOVEMENT][PACKET] ACCEPT ")
                || (message.startsWith("ATTACK ") && message.contains(" decision=ACCEPT "))) {
            return Style.ACCEPT;
        }
        if (message.startsWith("[BOUNDARY] ROLLBACK")) {
            return Style.ROLLBACK;
        }
        if (message.startsWith("DEATH ")) {
            return Style.DEATH;
        }
        if (message.startsWith("ATTACK_PACKET ")) {
            return Style.PACKET;
        }
        return Style.SYSTEM;
    }
}
