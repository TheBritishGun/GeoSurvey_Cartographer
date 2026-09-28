package dev.openmap.live;

import dev.openmap.mgrs.Bounds;

public final class PollSchedule {

    public static final long MIN_INTERVAL_MILLIS = 1_000;

    public static final long MAX_BACKOFF_MILLIS = 5 * 60_000;

    private static final long DEFAULT_INTERVAL_MILLIS = 2_000;

    private static final long MAX_REQUESTED_INTERVAL_MILLIS = 60_000;

    private final long baseIntervalMillis;
    private long currentIntervalMillis;
    private int consecutiveFailures;

    public PollSchedule(long baseIntervalMillis) {
        this.baseIntervalMillis = Math.min(MAX_BACKOFF_MILLIS,
                Math.max(MIN_INTERVAL_MILLIS, baseIntervalMillis));
        this.currentIntervalMillis = this.baseIntervalMillis;
    }

    public long intervalMillis() {
        return currentIntervalMillis;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isFailing() {
        return consecutiveFailures > 0;
    }

    public void succeeded() {
        consecutiveFailures = 0;
        currentIntervalMillis = baseIntervalMillis;
    }

    public void failed() {
        consecutiveFailures++;
        long doubled = currentIntervalMillis << 1;
        currentIntervalMillis = Math.min(MAX_BACKOFF_MILLIS, doubled);
    }

    public boolean shouldReportFailure() {
        return consecutiveFailures == 1;
    }

    public static long clampInterval(long requestedMillis) {
        if (requestedMillis <= 0) {
            return DEFAULT_INTERVAL_MILLIS;
        }
        return Bounds.clamp(requestedMillis, MIN_INTERVAL_MILLIS, MAX_REQUESTED_INTERVAL_MILLIS);
    }
}
