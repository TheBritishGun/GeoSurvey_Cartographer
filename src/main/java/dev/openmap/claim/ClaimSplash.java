package dev.openmap.claim;

import dev.openmap.live.LiveSnapshot;
import dev.openmap.live.OpenMapParser;
import dev.openmap.map.LabelText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClaimSplash implements GreetingLearner.Standing {

    public static final long DEFAULT_CHECK_PERIOD_MILLIS = 1000L;

    private static final int MAX_TEXT_CHARS = 64;

    private static final int MAX_TEXT_SCAN = MAX_TEXT_CHARS * 3;

    public enum Kind { ENTER, LEAVE }

    public record Text(String title, String subtitle) {
        public Text {
            title = LabelText.clean(title, MAX_TEXT_CHARS, MAX_TEXT_SCAN, true);
            subtitle = LabelText.clean(subtitle, MAX_TEXT_CHARS, MAX_TEXT_SCAN, true);
        }
    }

    @FunctionalInterface
    public interface Notes {
        // claimId may be null; null when nothing is learned.
        Text find(String claimId, boolean entering);
    }

    public static final Notes NO_NOTES = (claimId, entering) -> null;

    public record Event(Kind kind, String claimId, Text text, boolean draw) {
    }

    // Client thread only.
    private Set<String> inside = Set.of();

    public List<Event> check(String world, double x, double z,
                             List<LiveSnapshot.Area> claims, boolean dynmapUp,
                             Notes playerNotes, Notes nodeNotes) {
        Map<String, LiveSnapshot.Area> here = claimsHere(world, x, z, claims);

        List<Event> enters = Collections.emptyList();
        for (Map.Entry<String, LiveSnapshot.Area> entry : here.entrySet()) {
            String id = entry.getKey();
            if (!inside.contains(id)) {
                Text text = dynmapUp ? null : textFor(id, true, entry.getValue(), playerNotes, nodeNotes);
                if (enters.isEmpty()) {
                    enters = new ArrayList<>();
                }
                enters.add(new Event(Kind.ENTER, id, text, !dynmapUp && text != null));
            }
        }

        List<Event> leaves = Collections.emptyList();
        for (String id : inside) {
            if (!here.containsKey(id)) {
                Text text = dynmapUp ? null : textFor(id, false, null, playerNotes, nodeNotes);
                if (leaves.isEmpty()) {
                    leaves = new ArrayList<>();
                }
                leaves.add(new Event(Kind.LEAVE, id, text, !dynmapUp && text != null));
            }
        }

        inside = retainIds(here);

        List<Event> events = enters;
        if (!leaves.isEmpty()) {
            if (!enters.isEmpty()) {
                leaves.addAll(enters);
            }
            events = leaves;
        }
        return events;
    }

    public void resyncInside(String world, double x, double z, List<LiveSnapshot.Area> claims) {
        Set<String> nowInside = Collections.emptySet();
        String prepared = preparedWorld(world);
        for (LiveSnapshot.Area claim : claims) {
            if (holdsAt(prepared, claim, x, z)) {
                if (nowInside.isEmpty()) {
                    nowInside = new LinkedHashSet<>();
                }
                nowInside.add(claim.id());
            }
        }
        inside = nowInside;
    }

    // Read-only; learned notes only.
    public List<Text> refreshInside(String world, double x, double z,
                                    List<LiveSnapshot.Area> claims,
                                    Notes playerNotes, Notes nodeNotes) {
        if (inside.isEmpty()) {
            return Collections.emptyList();
        }
        List<Text> late = Collections.emptyList();
        String prepared = preparedWorld(world);
        for (LiveSnapshot.Area claim : claims) {
            if (!inside.contains(claim.id())) {
                continue;
            }
            if (holdsAt(prepared, claim, x, z)) {
                Text text = textFor(claim.id(), true, null, playerNotes, nodeNotes);
                if (text != null) {
                    if (late.isEmpty()) {
                        late = new ArrayList<>();
                    }
                    late.add(text);
                }
            }
        }
        return late;
    }

    // As of the last check or resync.
    @Override
    public boolean standsIn(String claimId) {
        return inside.contains(claimId);
    }

    static String preparedWorld(String world) {
        if (world == null || world.isBlank()) {
            return null;
        }
        return OpenMapParser.bareWorld(world);
    }

    // prepared comes from preparedWorld.
    static boolean holdsAt(String prepared, LiveSnapshot.Area claim, double x, double z) {
        if (prepared == null) {
            return false;
        }
        String stated = claim.world();
        if (stated == null || stated.isBlank()) {
            return false;
        }
        return OpenMapParser.bareWorld(stated).equalsIgnoreCase(prepared) && claim.contains(x, z);
    }

    private static Map<String, LiveSnapshot.Area> claimsHere(String world, double x, double z,
                                                            List<LiveSnapshot.Area> claims) {
        Map<String, LiveSnapshot.Area> here = Collections.emptyMap();
        String prepared = preparedWorld(world);
        for (LiveSnapshot.Area claim : claims) {
            if (holdsAt(prepared, claim, x, z)) {
                if (here.isEmpty()) {
                    here = new LinkedHashMap<>();
                }
                here.put(claim.id(), claim);
            }
        }
        return here;
    }

    private static Set<String> retainIds(Map<String, LiveSnapshot.Area> here) {
        for (Map.Entry<String, LiveSnapshot.Area> entry : here.entrySet()) {
            entry.setValue(null);
        }
        return here.keySet();
    }

    private static Text textFor(String claimId, boolean entering, LiveSnapshot.Area claim,
                                Notes playerNotes, Notes nodeNotes) {
        Text text = playerNotes == null ? null : playerNotes.find(claimId, entering);
        if (text == null) {
            text = nodeNotes == null ? null : nodeNotes.find(claimId, entering);
        }
        if (text == null && entering && claim != null) {
            ClaimLabel label = ClaimLabel.split(claim.label());
            text = new Text(label.title(), label.subtitle());
        }
        return text;
    }
}
