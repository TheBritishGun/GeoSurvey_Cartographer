package dev.openmap.claim;

import dev.openmap.live.LiveSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class GreetingLearner {

    public static final long WINDOW_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    public static final long WINDOW_NANOS =
            TimeUnit.MILLISECONDS.toNanos(ClaimSplash.DEFAULT_CHECK_PERIOD_MILLIS)
                    + WINDOW_MARGIN_NANOS;

    public static final int CONFIRMATIONS_FOR_REPORTABLE = 2;

    private static final int STANDING_SAMPLES = 64;

    private static final int STANDING_CLAIMS = 8;

    private static final int STANDING_OVERFLOW = -1;

    public record Note(String title, String subtitle) {
    }

    public record Pairing(String world, String claimId, boolean entering) {
    }

    @FunctionalInterface
    public interface Standing {
        boolean standsIn(String claimId);
    }

    private record Key(String server, String world, String claimId, boolean entering) {
    }

    private record Pending(String server, String world, String claimId,
                           boolean entering, long atNanos) {
    }

    private static final class Learned {
        private Note text;
        private int streak;

        Learned(Note text) {
            this.text = text;
            this.streak = 1;
        }

        Note text() {
            return text;
        }

        int streak() {
            return streak;
        }

        void bump() {
            streak++;
        }
    }

    private final List<Pending> pending = new ArrayList<>();

    private final Map<Key, Learned> learned = new HashMap<>();

    private final long[] standingAtNanos = new long[STANDING_SAMPLES];

    private final int[] standingCounts = new int[STANDING_SAMPLES];

    private final String[][] standingClaimIds = new String[STANDING_SAMPLES][STANDING_CLAIMS];

    private int standingNext = 0;

    private int standingFilled = 0;

    public void entered(String server, String world, String claimId, long atNanos) {
        trim(atNanos);
        pending.add(new Pending(server, world, claimId, true, atNanos));
    }

    public void left(String server, String world, String claimId, long atNanos) {
        trim(atNanos);
        pending.add(new Pending(server, world, claimId, false, atNanos));
    }

    // Records the claims at (x, z) while a crossing is unpaired.
    public void stoodAt(String world, double x, double z, List<LiveSnapshot.Area> claims,
                        long atNanos) {
        if (pending.isEmpty()) {
            return;
        }
        trim(atNanos);
        if (pending.isEmpty()) {
            return;
        }
        int sample = standingNext;
        int next = sample + 1;
        standingNext = (next == STANDING_SAMPLES) ? 0 : next;
        if (standingFilled < STANDING_SAMPLES) {
            standingFilled++;
        }
        String[] ids = standingClaimIds[sample];
        String prepared = ClaimSplash.preparedWorld(world);
        int count = 0;
        boolean overflow = false;
        for (int i = 0; i < claims.size(); i++) {
            LiveSnapshot.Area claim = claims.get(i);
            if (!ClaimSplash.holdsAt(prepared, claim, x, z)) {
                continue;
            }
            if (count < STANDING_CLAIMS) {
                ids[count] = claim.id();
                count++;
            } else {
                overflow = true;
                break;
            }
        }
        standingAtNanos[sample] = atNanos;
        standingCounts[sample] = overflow ? STANDING_OVERFLOW : count;
    }

    int pendingCount() {
        return pending.size();
    }

    public Optional<Pairing> serverText(String server, String title, String subtitle,
                                        long atNanos, Standing standing) {
        trim(atNanos);

        Pending matched = onlyCandidate(server, atNanos, standing);
        if (matched == null) {
            return Optional.empty();
        }
        pending.remove(matched);
        learn(new Key(matched.server(), matched.world(), matched.claimId(), matched.entering()),
                new Note(title, subtitle));
        return Optional.of(new Pairing(matched.world(), matched.claimId(), matched.entering()));
    }

    private Pending onlyCandidate(String server, long atNanos, Standing standing) {
        Pending only = null;
        int count = 0;
        for (Pending p : pending) {
            if (p.server().equals(server) && p.atNanos() <= atNanos) {
                count++;
                only = p;
                if (count > 1) {
                    break;
                }
            }
        }
        boolean teachable;
        if (count == 1) {
            teachable = standingAgrees(only, standing, atNanos);
        } else {
            teachable = false;
        }
        return teachable ? only : null;
    }

    private boolean standingAgrees(Pending crossing, Standing standing, long atNanos) {
        boolean inside = standing.standsIn(crossing.claimId());
        return crossing.entering() == inside
                && standingUnchanged(crossing.claimId(), inside, crossing.atNanos(), atNanos);
    }

    private boolean standingUnchanged(String claimId, boolean inside, long fromNanos,
                                      long toNanos) {
        if (standingFilled >= STANDING_SAMPLES && standingAtNanos[standingNext] > fromNanos) {
            return false;
        }
        for (int i = 0; i < standingFilled; i++) {
            long at = standingAtNanos[i];
            if (at < fromNanos || at > toNanos) {
                continue;
            }
            int count = standingCounts[i];
            if (count < 0) {
                return false;
            }
            boolean found = false;
            String[] ids = standingClaimIds[i];
            for (int j = 0; j < count; j++) {
                if (claimId.equals(ids[j])) {
                    found = true;
                    break;
                }
            }
            if (found != inside) {
                return false;
            }
        }
        return true;
    }

    private void learn(Key key, Note text) {
        Learned entry = learned.get(key);
        if (entry == null) {
            learned.put(key, new Learned(text));
        } else if (entry.text().equals(text)) {
            entry.bump();
        } else {
            entry.text = text;
            entry.streak = 1;
        }
    }

    public Optional<Note> reportableNote(String server, String world, String claimId,
                                         boolean entering) {
        Learned entry = learned.get(new Key(server, world, claimId, entering));
        return (entry != null) && (entry.streak() >= CONFIRMATIONS_FOR_REPORTABLE)
                ? Optional.of(entry.text())
                : Optional.empty();
    }

    private void trim(long atNanos) {
        long windowStart = atNanos - WINDOW_NANOS;
        int keep = 0;
        for (int i = 0; i < pending.size(); i++) {
            Pending event = pending.get(i);
            if (event.atNanos() >= windowStart) {
                pending.set(keep, event);
                keep++;
            }
        }
        for (int i = pending.size() - 1; i >= keep; i--) {
            pending.remove(i);
        }
    }
}
