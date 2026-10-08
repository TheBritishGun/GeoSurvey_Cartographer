package dev.openmap.client;

import dev.openmap.api.Registration;

// Removes one listener from the part that holds it.
final class CollectorListenerRegistration implements Registration {

    private final Runnable removal;

    // Client thread.
    private boolean closed = false;

    CollectorListenerRegistration(Runnable removal) {
        this.removal = removal;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        removal.run();
    }
}
