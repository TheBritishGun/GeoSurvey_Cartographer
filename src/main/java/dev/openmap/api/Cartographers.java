package dev.openmap.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class Cartographers {

    private static final AtomicReference<Cartographer> RUNNING = new AtomicReference<>();

    private Cartographers() {
    }

    // Client thread. Null before the collector starts and after it stops.
    public static Cartographer running() {
        return RUNNING.get();
    }

    // Only the collector's entrypoint calls this.
    public static void publish(Cartographer cartographer) {
        RUNNING.set(Objects.requireNonNull(cartographer, "cartographer"));
    }

    // Only the collector's entrypoint calls this.
    public static void withdraw(Cartographer cartographer) {
        RUNNING.compareAndSet(cartographer, null);
    }
}
