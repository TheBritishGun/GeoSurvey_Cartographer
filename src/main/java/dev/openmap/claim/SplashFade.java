package dev.openmap.claim;

public final class SplashFade {

    public static final int FADE_IN_TICKS = 10;

    public static final int STAY_TICKS = 70;

    public static final int FADE_OUT_TICKS = 20;

    public static final int TOTAL_TICKS = FADE_IN_TICKS + STAY_TICKS + FADE_OUT_TICKS;

    public static final double MAX_ALPHA = 255.0;

    private SplashFade() {
    }

    // 0 to 255, truncated toward zero.
    public static int alpha(double elapsedTicks) {
        double remaining = TOTAL_TICKS - elapsedTicks;
        int alpha;
        if (remaining > STAY_TICKS + FADE_OUT_TICKS) {
            double raw = (TOTAL_TICKS - remaining) * MAX_ALPHA / FADE_IN_TICKS;
            alpha = (int) Math.max(0, raw);
        } else if (remaining <= FADE_OUT_TICKS) {
            if (remaining <= 0) {
                alpha = 0;
            } else {
                double raw = remaining * MAX_ALPHA / FADE_OUT_TICKS;
                alpha = (int) Math.max(0, raw);
            }
        } else {
            alpha = (int) MAX_ALPHA;
        }
        return alpha;
    }
}
