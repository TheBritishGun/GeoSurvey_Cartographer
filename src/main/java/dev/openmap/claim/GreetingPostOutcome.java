package dev.openmap.claim;

public final class GreetingPostOutcome {

    public static final long DEFAULT_RETRY_SECONDS = 60L;

    public static final long UNREACHABLE_RETRY_SECONDS = 3600L;

    private static final Outcome SENT = new Outcome(Action.SENT, 0L);

    private static final Outcome REFUSED = new Outcome(Action.REFUSED, 0L);

    private static final Outcome UNREACHABLE = new Outcome(Action.LOG_AND_RETRY_LATER,
            UNREACHABLE_RETRY_SECONDS);

    private static final int HTTP_STATUS_CLASS_SIZE = 100;

    private static final int HTTP_SUCCESS_CLASS = 2;

    private static final int FIRST_HTTP_SUCCESS_STATUS = HTTP_SUCCESS_CLASS * HTTP_STATUS_CLASS_SIZE;

    private static final int LAST_HTTP_SUCCESS_STATUS = FIRST_HTTP_SUCCESS_STATUS
            + HTTP_STATUS_CLASS_SIZE - 1;

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final int HTTP_BAD_REQUEST = 400;

    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    public enum Action {
        SENT,
        RETRY_AFTER,
        LOG_AND_RETRY_LATER,
        REFUSED
    }

    public record Outcome(Action action, long retryAfterSeconds) {
    }

    private GreetingPostOutcome() {
    }

    public static Outcome decide(int status, long retryAfterSeconds) {
        if (status >= FIRST_HTTP_SUCCESS_STATUS && status <= LAST_HTTP_SUCCESS_STATUS) {
            return SENT;
        }
        if (status == HTTP_TOO_MANY_REQUESTS) {
            long wait = retryAfterSeconds > 0 ? retryAfterSeconds : DEFAULT_RETRY_SECONDS;
            return new Outcome(Action.RETRY_AFTER, Math.min(wait, UNREACHABLE_RETRY_SECONDS));
        }
        if (status == HTTP_BAD_REQUEST || status == HTTP_PAYLOAD_TOO_LARGE) {
            return REFUSED;
        }
        return UNREACHABLE;
    }
}
