package dev.openmap.api;

public interface Cartographer {

    // Any thread. The first API version is 1.
    int apiVersion();

    // Any thread.
    CartographerWorld world();

    // Any thread.
    CartographerGround ground();

    // Any thread.
    CartographerMarkers markers();
}
