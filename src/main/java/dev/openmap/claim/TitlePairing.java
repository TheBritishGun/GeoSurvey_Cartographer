package dev.openmap.claim;

public final class TitlePairing {

    public static final State NONE = new State(-1, "", "", false);

    private static final Flushed IDLE = new Flushed(NONE, "", "", false);

    public record State(long pendingTick, String pendingTitle, String pendingSubtitle,
                        boolean hasPending) {
    }

    public record Flushed(State next, String title, String subtitle, boolean fire) {
    }

    private TitlePairing() {
    }

    public static State title(State state, long tick, String text) {
        String subtitle = state.pendingTick() == tick ? state.pendingSubtitle() : "";
        return new State(tick, text == null ? "" : text, subtitle, true);
    }

    public static State subtitle(State state, long tick, String text) {
        if (!state.hasPending()) {
            return new State(tick, "", text == null ? "" : text, false);
        }
        if (state.pendingTick() != tick) {
            return state;
        }
        return new State(tick, state.pendingTitle(), text == null ? "" : text, true);
    }

    public static Flushed flush(State state, long tick) {
        if (!state.hasPending()) {
            return IDLE;
        }
        if (state.pendingTick() != tick) {
            return new Flushed(state, "", "", false);
        }
        return new Flushed(NONE, state.pendingTitle(), state.pendingSubtitle(), true);
    }
}
