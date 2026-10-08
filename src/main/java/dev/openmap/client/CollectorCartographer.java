package dev.openmap.client;

import dev.openmap.api.Cartographer;
import dev.openmap.api.CartographerGround;
import dev.openmap.api.CartographerMarkers;
import dev.openmap.api.CartographerWorld;

// The running collector as GeoSurvey reaches it, in 3 parts.
final class CollectorCartographer implements Cartographer {

    private static final int API_VERSION = 1;

    private final CartographerWorld world;

    private final CartographerGround ground;

    private final CartographerMarkers markers;

    CollectorCartographer(CartographerWorld world, CartographerGround ground, CartographerMarkers markers) {
        this.world = world;
        this.ground = ground;
        this.markers = markers;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public CartographerWorld world() {
        return world;
    }

    @Override
    public CartographerGround ground() {
        return ground;
    }

    @Override
    public CartographerMarkers markers() {
        return markers;
    }
}
