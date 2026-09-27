package net.peercraft.network.handoff;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Binds deferred native loading and publication to one attempt and one server instance. */
public final class HandoffLaunchContext {
    private static final ThreadLocal<HandoffLaunchContext> entering = new ThreadLocal<>();
    public static void enter(HandoffLaunchContext context, Runnable open) {
        entering.set(context); try { open.run(); } finally { entering.remove(); }
    }
    private static final Map<Path, HandoffLaunchContext> pending = new HashMap<>();
    private static final Map<Object, HandoffLaunchContext> loads = new WeakHashMap<>();
    public final UUID attempt = UUID.randomUUID();
    public final Path world;
    private final BooleanSupplier active;
    private Object server;
    private HandoffLaunchContext(Path path, BooleanSupplier active) { world = path.toAbsolutePath().normalize(); this.active = active; }
    public static synchronized HandoffLaunchContext begin(Path path, BooleanSupplier active) {
        HandoffLaunchContext context = new HandoffLaunchContext(path, active);
        if (pending.size() >= 64) pending.entrySet().removeIf(e -> !e.getValue().active.getAsBoolean());
        if (pending.size() >= 64) throw new IllegalStateException("Too many active native loads");
        HandoffLaunchContext previous = pending.get(context.world);
        if (previous != null && previous.active.getAsBoolean()) throw new IllegalStateException("This world has an active native launch");
        pending.put(context.world, context); return context;
    }
    public static synchronized void capture(Object access, Path path) {
        HandoffLaunchContext context = pending.get(path.toAbsolutePath().normalize());
        if (context != null && context.active.getAsBoolean() && !loads.containsKey(access)) loads.put(access, context);
    }
    public static synchronized boolean permits(Object access) {
        HandoffLaunchContext context = loads.get(access);
        return context == null || context.active.getAsBoolean();
    }
    public static synchronized boolean bind(Object access, Object candidate, Path path) {
        HandoffLaunchContext context = loads.get(access);
        if (context == null) context = entering.get();
        if (context == null) return true;
        synchronized (context) {
            if (!context.world.equals(path.toAbsolutePath().normalize())) return true;
            if (context.server == null) context.server = candidate;
            return context.server == candidate && context.active.getAsBoolean();
        }
    }
    public synchronized boolean belongs(Object candidate, Path path) {
        return world.equals(path.toAbsolutePath().normalize()) && server == candidate;
    }
    public synchronized boolean owns(Object candidate, Path path) {
        if (!active.getAsBoolean() || !world.equals(path.toAbsolutePath().normalize())) return false;
        if (server == null) server = candidate;
        return server == candidate;
    }
}
