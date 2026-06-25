package io.runcycles.events.logging;

public final class LogSanitizer {
    private LogSanitizer() {
    }

    public static String safe(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
