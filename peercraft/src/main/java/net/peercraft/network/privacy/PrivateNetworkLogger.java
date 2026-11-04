package net.peercraft.network.privacy;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Redacts numeric network addresses before SLF4J formats arguments or exceptions. */
public final class PrivateNetworkLogger {
    private PrivateNetworkLogger() { }
    public static String redact(String value) { return NetworkPrivacy.redact(value); }

    public static Logger getLogger(String name) { Logger delegate=LogManager.getLogger(name); return Logger.class.isInterface() ? wrap(delegate) : delegate; }

    static Logger wrap(Logger delegate) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
            Object[] safe = args == null ? null : args.clone();
            if (safe != null && (method.getName().equals("trace") || method.getName().equals("debug")
                    || method.getName().equals("info") || method.getName().equals("warn") || method.getName().equals("error"))) {
                for (int i = 0; i < safe.length; i++) safe[i] = sanitize(safe[i]);
            }
            try { return method.invoke(delegate, safe); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    public static Object sanitize(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Object[]) {
            Object[] copy = ((Object[]) value).clone();
            for (int i = 0; i < copy.length; i++) copy[i] = sanitize(copy[i]);
            return copy;
        }
        if (value instanceof Throwable) {
            return sanitizeThrowable((Throwable) value, new java.util.IdentityHashMap<Throwable, Throwable>());
        }
        return redact(String.valueOf(value));
    }
    private static Throwable sanitizeThrowable(Throwable original, java.util.IdentityHashMap<Throwable, Throwable> seen) {
        if (seen.containsKey(original)) return seen.get(original);
        Throwable safe = new Throwable(redact(original.getClass().getName() + ": " + original.getMessage()));
        seen.put(original, safe);
        safe.setStackTrace(original.getStackTrace());
        if (original.getCause() != null && original.getCause() != original)
            safe.initCause(sanitizeThrowable(original.getCause(), seen));
        for (Throwable suppressed : original.getSuppressed())
            if (suppressed != original) safe.addSuppressed(sanitizeThrowable(suppressed, seen));
        return safe;
    }
}
