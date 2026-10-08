package dev.openmap.api;

public interface Registration extends AutoCloseable {

    // Client thread. A second call does nothing.
    @Override
    void close();
}
