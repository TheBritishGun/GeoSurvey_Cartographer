package dev.openmap.client;

import dev.openmap.config.LandNavConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class CollectorSettings {

    // Colour when the settings will not do what was asked.
    static final int WARN = 0xFFE0A33A;

    // Colour when nothing is being sent.
    static final int MUTED = 0xFF9A9A9A;

    // Colour when the settings will do what was asked.
    static final int GOOD = 0xFF88CC88;

    private CollectorSettings() {
    }

    record Draft(String collector, boolean enabled, boolean sessionProof,
                 boolean gating, String servers) {
        Draft(String collector, boolean enabled, boolean gating, String servers) {
            this(collector, enabled, false, gating, servers);
        }
    }

    // May leave the collector address and the ground switch unwritten when the address is refused.
    static void write(LandNavConfig config, Draft draft) {
        String typed = draft.collector().trim();
        String stored = config.shareCollector == null
                ? "" : config.shareCollector.trim();
        boolean refused = ShareCommand.cleartextAwayFromHome(typed);
        if (refused && draft.enabled() && !typed.equals(stored)) {
            if (!draft.sessionProof()) {
                config.shareSessionProof = false;
            }
            if (draft.gating() && config.approvedServersConfigured) {
                List<String> kept = keysOf(draft.servers());
                List<String> armed = ChunkCapture.approvedServers(config);
                if (kept.size() < armed.size() && armed.containsAll(kept)) {
                    armed.clear();
                    armed.addAll(kept);
                }
            }
            return;
        }
        if (!refused) {
            config.shareCollector = typed;
        }
        config.shareEnabled = draft.enabled();
        config.shareSessionProof = draft.sessionProof();

        List<String> keys = keysOf(draft.servers());
        // approvedServers returns the live list, not a copy.
        List<String> live = ChunkCapture.approvedServers(config);
        live.clear();
        live.addAll(draft.gating() ? keys : List.of());
        config.approvedServersConfigured = draft.gating();
    }

    // Drops pieces that do not parse and duplicates; keeps order.
    static List<String> keysOf(String servers) {
        Set<String> keys = new LinkedHashSet<>();
        for (String piece : servers.split(",")) {
            String host = ChunkCapture.serverKey(piece);
            if (!host.isEmpty()) {
                keys.add(ChunkCapture.serverWorldKey(piece));
            }
        }
        return new ArrayList<>(keys);
    }

    static List<String> droppedKeys(String servers) {
        List<String> dropped = new ArrayList<>();
        for (String piece : servers.split(",")) {
            if (piece.isBlank()) {
                continue;
            }
            if (ChunkCapture.serverKey(piece).isEmpty()) {
                dropped.add(piece.trim());
            }
        }
        return dropped;
    }

    record Status(Component text, int colour) {
    }

    enum Where { ALL, NONE, LISTED }

    static Where whereOf(boolean gating, List<String> keys) {
        if (!gating) {
            return Where.ALL;
        }
        return keys.isEmpty() ? Where.NONE : Where.LISTED;
    }

    private static Component whereComponent(Where whereState, List<String> keys) {
        return switch (whereState) {
            case ALL -> Component.translatable("openmap-collect.status.gate_all");
            case NONE -> Component.translatable("openmap-collect.status.gate_none");
            case LISTED -> Component.translatable("openmap-collect.status.gate_listed",
                    String.join(", ", keys));
        };
    }

    static Status status(Draft draft) {
        String address = draft.collector().trim();
        if (address.isEmpty()) {
            return new Status(
                    Component.translatable("openmap-collect.status.no_address"), WARN);
        }
        if (!ShareCommand.looksUsable(address)) {
            return new Status(
                    Component.translatable("openmap-collect.status.not_an_address"),
                    WARN);
        }
        if (ShareCommand.cleartextAwayFromHome(address)) {
            return new Status(
                    Component.translatable("openmap-collect.status.cleartext"), WARN);
        }
        if (!draft.enabled()) {
            return new Status(
                    Component.translatable("openmap-collect.status.off"), MUTED);
        }
        List<String> keys = draft.gating() ? keysOf(draft.servers()) : List.of();
        Where whereState = whereOf(draft.gating(), keys);
        Component where = whereComponent(whereState, keys);
        boolean surveysNothing = whereState == Where.NONE;
        MutableComponent line =
                Component.translatable("openmap-collect.status.sending", address)
                        .append(" ").append(where);
        return new Status(line, surveysNothing ? WARN : GOOD);
    }
}
