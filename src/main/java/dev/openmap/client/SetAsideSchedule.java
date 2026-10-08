package dev.openmap.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

final class SetAsideSchedule {

    static final String MARKER_SUFFIX = ".retry";

    static final String UNBUILT_MARKER_SUFFIX = ".unbuilt";

    private static final int MAX_REFUSALS_COUNTED = 32;

    private static final int NO_MARKER = 0;

    private static final long HOUR_MILLIS = 60L * 60L * 1000L;

    private static final long DAY_MILLIS = 24L * HOUR_MILLIS;

    private static final long FIRST_WAIT_MILLIS = DAY_MILLIS;

    private static final long CEILING_WAIT_MILLIS = 16L * DAY_MILLIS;

    private static final int DOUBLINGS_TO_THE_CEILING =
            Long.numberOfTrailingZeros(CEILING_WAIT_MILLIS / FIRST_WAIT_MILLIS);

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final byte[] NO_BYTES = new byte[0];

    private static final StandardOpenOption[] MARKER_OPTIONS = {
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING};

    private record Waiting(long dueNanos, int refusals) {
    }

    private final String refusedSuffix;

    private final Map<Path, Waiting> waiting = new HashMap<>();

    private long soonestNanos = 0L;

    SetAsideSchedule(String refusedSuffix) {
        this.refusedSuffix = refusedSuffix;
    }

    Path refusedBeside(Path marker) {
        return marker.resolveSibling(
                stem(marker.getFileName().toString(), MARKER_SUFFIX) + refusedSuffix);
    }

    int nextRefusals(Path refused) {
        return Math.clamp(refusalsBeside(refused) + 1, 1, MAX_REFUSALS_COUNTED);
    }

    boolean mark(Path refused, int refusals) {
        Path marker = markerBeside(refused);
        try {
            Files.write(marker, refusals > 1 ? new byte[refusals - 1] : NO_BYTES,
                    MARKER_OPTIONS);
            Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | RuntimeException notWritten) {
            return false;
        }
        return Files.isRegularFile(marker);
    }

    boolean markUnbuilt(Path refused) {
        try {
            Files.write(unbuiltMarkerBeside(refused), NO_BYTES, MARKER_OPTIONS);
        } catch (IOException | RuntimeException notWritten) {
            return false;
        }
        return Files.isRegularFile(unbuiltMarkerBeside(refused));
    }

    boolean hasRetryMark(Path refused) {
        return Files.isRegularFile(markerBeside(refused));
    }

    boolean hasUnbuiltMark(Path refused) {
        return Files.isRegularFile(unbuiltMarkerBeside(refused));
    }

    static int refusalsFrom(long markerSize) {
        return 1 + (int) Math.min(markerSize, MAX_REFUSALS_COUNTED - 1L);
    }

    static long waitHours(int refusals) {
        return waitMillis(refusals) / HOUR_MILLIS;
    }

    static long longestWaitDays() {
        return CEILING_WAIT_MILLIS / DAY_MILLIS;
    }

    void refusedAgain(Path refused, int refusals, long nowNanos) {
        waitUntil(refused, refusals, nowNanos + waitMillis(refusals) * NANOS_PER_MILLI);
    }

    void waitsOut(Path refused, int refusals, long refusedAtMillis, long wallNowMillis,
                  long nowNanos) {
        long waitMillis = waitMillis(refusals);
        long passedMillis = wallNowMillis - refusedAtMillis;
        long leftMillis;
        if (passedMillis < 0L) {
            leftMillis = waitMillis;
        } else if (passedMillis >= waitMillis) {
            leftMillis = 0L;
        } else {
            leftMillis = waitMillis - passedMillis;
        }
        waitUntil(refused, refusals, nowNanos + leftMillis * NANOS_PER_MILLI);
    }

    void stopWaiting(Path refused) {
        if (waiting.remove(refused) != null && !waiting.isEmpty()) {
            soonestNanos = soonest();
        }
    }

    static long timeOf(Path marker, long wallNowMillis) {
        try {
            return Files.getLastModifiedTime(marker).toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return wallNowMillis;
        }
    }

    IOException deleteMark(Path refused) {
        IOException notDeleted;
        try {
            Files.deleteIfExists(markerBeside(refused));
            notDeleted = null;
        } catch (IOException | RuntimeException couldNotDelete) {
            notDeleted = couldNotDelete instanceof IOException failure
                    ? failure : new IOException(couldNotDelete);
        }
        return notDeleted;
    }

    boolean dueAtOrBefore(long nowNanos) {
        return !waiting.isEmpty() && nowNanos - soonestNanos >= 0L;
    }

    // Null when nothing is due.
    Path nextDue(long nowNanos) {
        if (!dueAtOrBefore(nowNanos)) {
            return null;
        }
        Path due = null;
        long dueNanos = 0L;
        for (Map.Entry<Path, Waiting> each : waiting.entrySet()) {
            long at = each.getValue().dueNanos();
            if (nowNanos - at >= 0L && (due == null || at - dueNanos < 0L)) {
                due = each.getKey();
                dueNanos = at;
            }
        }
        return due;
    }

    int refusalsOf(Path refused) {
        Waiting held = waiting.get(refused);
        return held == null ? NO_MARKER : held.refusals();
    }

    private Path markerBeside(Path refused) {
        return refused.resolveSibling(
                stem(refused.getFileName().toString(), refusedSuffix) + MARKER_SUFFIX);
    }

    private Path unbuiltMarkerBeside(Path refused) {
        return refused.resolveSibling(
                stem(refused.getFileName().toString(), refusedSuffix) + UNBUILT_MARKER_SUFFIX);
    }

    private int refusalsBeside(Path refused) {
        return refusalsIn(markerBeside(refused));
    }

    private static int refusalsIn(Path marker) {
        long counted;
        try {
            counted = Files.size(marker);
        } catch (IOException | RuntimeException unreadable) {
            return NO_MARKER;
        }
        return refusalsFrom(counted);
    }

    private static long waitMillis(int refusals) {
        return FIRST_WAIT_MILLIS << Math.min(Math.max(refusals, 1) - 1,
                DOUBLINGS_TO_THE_CEILING);
    }

    private void waitUntil(Path refused, int refusals, long dueNanos) {
        waiting.put(refused, new Waiting(dueNanos, refusals));
        soonestNanos = soonest();
    }

    private long soonest() {
        long soonest = 0L;
        boolean found = false;
        for (Waiting each : waiting.values()) {
            if (!found || each.dueNanos() - soonest < 0L) {
                soonest = each.dueNanos();
                found = true;
            }
        }
        return soonest;
    }

    private static String stem(String name, String suffix) {
        return name.length() > suffix.length()
                ? name.substring(0, name.length() - suffix.length()) : name;
    }
}
