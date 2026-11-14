package de.raindancer118.shelved.scan;

import de.raindancer118.shelved.config.ShelvedProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Every scan costs real money at Anthropic, so each user gets a budget per hour. */
@Component
public class RateLimiter {

    public static class LimitExceeded extends RuntimeException {
        public LimitExceeded() {
            super("Zu viele Scans in der letzten Stunde, bitte kurz warten");
        }
    }

    private final int perHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(ShelvedProperties props) {
        this(props.scansPerHour(), Clock.systemUTC());
    }

    RateLimiter(int perHour, Clock clock) {
        this.perHour = perHour;
        this.clock = clock;
    }

    public void acquire(String owner) {
        Deque<Instant> q = calls.computeIfAbsent(owner, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant now = clock.instant();
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                q.pollFirst();
            }
            if (q.size() >= perHour) {
                throw new LimitExceeded();
            }
            q.addLast(now);
        }
    }
}
