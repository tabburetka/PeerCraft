package org.slf4j;

import org.apache.logging.log4j.LogManager;

/**
 * Minimal SLF4J → Log4j2 shim for the Forge 1.12.2 backport. Minecraft 1.12.2 ships Log4j 2
 * but no SLF4J facade, and the 1.12.2 Forge classpath doesn't provide one; PeerCraft's shared
 * networking/config code logs through {@code org.slf4j}. Rather than pull in (and shade — no
 * JiJ on 1.12.2) {@code slf4j-api} + a binding, this reproduces just the handful of methods
 * that code uses. Log4j2's {@code {}} placeholder syntax and trailing-{@code Throwable}
 * detection match SLF4J's, so calls forward unchanged.
 */
public final class Logger {

    private final org.apache.logging.log4j.Logger delegate;

    Logger(String name) {
        this.delegate = LogManager.getLogger(name);
    }

    public void debug(String msg) { delegate.debug(msg); }
    public void debug(String fmt, Object arg) { delegate.debug(fmt, arg); }
    public void debug(String fmt, Object a, Object b) { delegate.debug(fmt, a, b); }
    public void debug(String fmt, Object... args) { delegate.debug(fmt, args); }

    public void info(String msg) { delegate.info(msg); }
    public void info(String fmt, Object arg) { delegate.info(fmt, arg); }
    public void info(String fmt, Object a, Object b) { delegate.info(fmt, a, b); }
    public void info(String fmt, Object... args) { delegate.info(fmt, args); }

    public void warn(String msg) { delegate.warn(msg); }
    public void warn(String fmt, Object arg) { delegate.warn(fmt, arg); }
    public void warn(String fmt, Object a, Object b) { delegate.warn(fmt, a, b); }
    public void warn(String fmt, Object... args) { delegate.warn(fmt, args); }

    public void error(String msg) { delegate.error(msg); }
    public void error(String fmt, Object arg) { delegate.error(fmt, arg); }
    public void error(String fmt, Object a, Object b) { delegate.error(fmt, a, b); }
    public void error(String fmt, Object... args) { delegate.error(fmt, args); }

    public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
}
