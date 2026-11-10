package org.slf4j;

/** See {@link Logger} — minimal SLF4J → Log4j2 shim for the Forge 1.12.2 backport. */
public final class LoggerFactory {

    private LoggerFactory() {
    }

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    public static Logger getLogger(Class<?> type) {
        return new Logger(type.getName());
    }
}
