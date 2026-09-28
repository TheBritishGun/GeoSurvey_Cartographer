package dev.openmap.share;

public final class SendRate {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long last;

    public SendRate(double burst, double perSecond) {
        if (!Double.isFinite(burst) || !Double.isFinite(perSecond)
                || burst < 1.0 || perSecond <= 0) {
            throw new IllegalArgumentException(
                    "burst must be finite and at least 1; perSecond must be finite and positive");
        }
        this.capacity = burst;
        this.refillPerNano = perSecond / NANOS_PER_SECOND;
        this.tokens = burst;
        this.last = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        double t = refill();
        if (t < 1.0) {
            return false;
        }
        tokens = t - 1.0;
        return true;
    }

    public synchronized double available() {
        return peek();
    }

    public synchronized long nanosUntilToken() {
        double t = peek();
        if (t >= 1.0) {
            return 0L;
        }
        return (long) Math.ceil((1.0 - t) / refillPerNano);
    }

    private double refill() {
        long now = System.nanoTime();
        long elapsed = now - last;
        if (elapsed > 0) {
            double t = tokens + (double) elapsed * refillPerNano;
            last = now;
            tokens = t < capacity ? t : capacity;
            return tokens;
        }
        return tokens;
    }

    private double peek() {
        long now = System.nanoTime();
        long elapsed = now - last;
        if (elapsed <= 0) {
            return tokens;
        }
        double t = tokens + (double) elapsed * refillPerNano;
        return t < capacity ? t : capacity;
    }
}
