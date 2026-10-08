package dev.openmap.claim;

import dev.openmap.live.LiveSnapshot;
import java.util.concurrent.TimeUnit;

public final class DynmapSignal {

    public static final long NODE_FETCH_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    public enum PollOutcome {
        SUCCESS,
        FAILURE,
        NOTHING
    }

    private DynmapSignal() {
    }

    // Long.MIN_VALUE means that poll never happened.
    public static boolean up(long lastSuccessAtNanos, long lastFailureAtNanos,
                             long nowNanos) {
        if (lastSuccessAtNanos == Long.MIN_VALUE) {
            return lastFailureAtNanos == Long.MIN_VALUE;
        }
        if (lastFailureAtNanos > lastSuccessAtNanos) {
            return false;
        }
        return nowNanos - lastSuccessAtNanos <= LiveSnapshot.STALE_AFTER_NANOS;
    }

    public static PollOutcome pollOutcome(boolean requested, boolean answered) {
        if (!requested) {
            return PollOutcome.NOTHING;
        }
        return answered ? PollOutcome.SUCCESS : PollOutcome.FAILURE;
    }

    // Long.MIN_VALUE means never submitted.
    public static boolean nodeFetchDue(long lastSubmittedAtNanos, long nowNanos) {
        return lastSubmittedAtNanos == Long.MIN_VALUE
                || nowNanos - lastSubmittedAtNanos >= NODE_FETCH_INTERVAL_NANOS;
    }

    public static boolean nodeCopyFresh(long lastAnsweredAtNanos, long nowNanos) {
        return lastAnsweredAtNanos != Long.MIN_VALUE
                && nowNanos - lastAnsweredAtNanos <= LiveSnapshot.STALE_AFTER_NANOS;
    }
}
