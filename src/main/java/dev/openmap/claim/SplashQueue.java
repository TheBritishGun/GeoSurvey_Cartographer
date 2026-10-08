package dev.openmap.claim;

import java.util.ArrayList;
import java.util.List;

public final class SplashQueue {

    private static final int MAX_PENDING = 8;

    public record State(List<ClaimSplash.Text> pending, ClaimSplash.Text showing,
                        long showingSinceNanos) {

        public State {
            pending = List.copyOf(pending);
        }

        public static final State EMPTY = new State(List.of(), null, -1L);
    }

    private SplashQueue() {
    }

    public static State offer(State state, List<ClaimSplash.Text> drawn) {
        if (drawn.isEmpty()) {
            return state;
        }
        List<ClaimSplash.Text> pending = state.pending();
        int total = pending.size() + drawn.size();
        int firstKept = Math.max(0, total - MAX_PENDING);
        List<ClaimSplash.Text> merged = new ArrayList<>(total - firstKept);
        int pendingKeptFrom = Math.min(firstKept, pending.size());
        for (int i = pendingKeptFrom; i < pending.size(); i++) {
            merged.add(pending.get(i));
        }
        int drawnKeptFrom = Math.max(0, firstKept - pending.size());
        for (int i = drawnKeptFrom; i < drawn.size(); i++) {
            merged.add(drawn.get(i));
        }
        return new State(merged, state.showing(), state.showingSinceNanos());
    }

    public static State advance(State state, long nowNanos, long checkPeriodNanos) {
        if (state.pending().isEmpty()) {
            return state;
        }
        boolean due = state.showing() == null
                || nowNanos - state.showingSinceNanos() >= checkPeriodNanos;
        if (!due) {
            return state;
        }
        List<ClaimSplash.Text> rest = state.pending().subList(1, state.pending().size());
        return new State(rest, state.pending().get(0), nowNanos);
    }
}
