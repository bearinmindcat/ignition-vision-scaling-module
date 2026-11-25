package io.github.bearinmindcat.visionscaling.client;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Optional trace for troubleshooting: -Dvisionscaling.debug=true appends to %TEMP%\visionscaling-debug.txt. */
final class Debug {

    static final boolean ON = Boolean.getBoolean("visionscaling.debug");
    private static final File FILE = new File(System.getProperty("java.io.tmpdir"), "visionscaling-debug.txt");

    private Debug() {
    }

    static synchronized void log(String format, Object... args) {
        if (!ON) {
            return;
        }
        try (FileWriter w = new FileWriter(FILE, true)) {
            w.write(System.currentTimeMillis() + " " + String.format(format, args) + System.lineSeparator());
        } catch (IOException | RuntimeException ignored) {
            // Tracing must never break the client.
        }
    }
}
