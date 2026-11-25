import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class TokenBucketTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected bucket decision");
    }

    public static void main(String[] args) throws Exception {
        var clock = new AtomicLong();
        var bucket = new TokenBucket(2, 2.0, clock::get);
        check(bucket.tryAcquire().remaining() == 1);
        check(bucket.tryAcquire().remaining() == 0);
        check(bucket.tryAcquire().retryAfterSeconds() == 1);
        clock.set(499_999_999);
        check(!bucket.tryAcquire().allowed());
        clock.set(500_000_000);
        check(bucket.tryAcquire().allowed());
        clock.set(20_000_000_000L);
        check(bucket.tryAcquire().remaining() == 1);

        var shared = new TokenBucket(10, 1, () -> 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = IntStream.range(0, 100)
                    .mapToObj(i -> (Callable<Boolean>) () -> shared.tryAcquire().allowed())
                    .toList();
            int allowed = 0;
            for (var result : executor.invokeAll(jobs)) {
                if (result.get()) allowed++;
            }
            check(allowed == 10);
        }
        System.out.println("Token bucket tests passed");
    }
}
