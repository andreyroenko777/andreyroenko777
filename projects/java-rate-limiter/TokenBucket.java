import java.util.function.LongSupplier;

public final class TokenBucket {
    private final int capacity;
    private final double tokensPerNanosecond;
    private final LongSupplier clock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int capacity, double tokensPerSecond) {
        this(capacity, tokensPerSecond, System::nanoTime);
    }

    TokenBucket(int capacity, double tokensPerSecond, LongSupplier clock) {
        if (capacity < 1 || tokensPerSecond <= 0 || !Double.isFinite(tokensPerSecond)) {
            throw new IllegalArgumentException("Capacity and refill rate must be positive and finite");
        }
        this.capacity = capacity;
        this.tokensPerNanosecond = tokensPerSecond / 1_000_000_000.0;
        if (tokensPerNanosecond == 0 || !Double.isFinite(1 / tokensPerNanosecond)) {
            throw new IllegalArgumentException("Refill rate is too small");
        }
        this.clock = clock;
        tokens = capacity;
        lastRefill = clock.getAsLong();
    }

    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {}

    public synchronized Decision tryAcquire() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNanosecond);
            lastRefill = now;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return new Decision(true, (int) tokens, 0);
        }
        long retryAfter = (long) Math.ceil((1 - tokens) / tokensPerNanosecond / 1_000_000_000.0);
        return new Decision(false, 0, retryAfter);
    }
}
