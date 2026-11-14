package de.raindancer118.shelved.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force brake in memory: 5 wrong passwords per address or 20 per IP within 15 minutes lock that key,
 * and an IP may trigger a limited number of mails (registration, reset) per hour. One instance, so memory is enough.
 */
@Component
public class LoginThrottle {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int PER_ADDRESS = 5;
    static final int PER_IP = 20;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> mails = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int mailsPerHour;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottle(@org.springframework.beans.factory.annotation.Value("${shelved.mails-per-hour:10}") int mailsPerHour) {
        this(Clock.systemUTC(), mailsPerHour);
    }

    LoginThrottle(Clock clock, int mailsPerHour) {
        this.clock = clock;
        this.mailsPerHour = mailsPerHour;
    }

    /** Forget expired entries so the maps don't grow with every address ever tried. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30M")
    void prune() {
        failures.keySet().forEach(k -> count(failures, k, WINDOW));
        failures.values().removeIf(java.util.Collection::isEmpty);
        mails.keySet().forEach(k -> count(mails, k, Duration.ofHours(1)));
        mails.values().removeIf(java.util.Collection::isEmpty);
    }

    public void check(String email, String ip) {
        if (count(failures, "m:" + email, WINDOW) >= PER_ADDRESS || count(failures, "i:" + ip, WINDOW) >= PER_IP) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Fehlversuche, bitte warte 15 Minuten");
        }
    }

    public void failed(String email, String ip) {
        add(failures, "m:" + email);
        add(failures, "i:" + ip);
    }

    public void succeeded(String email, String ip) {
        failures.remove("m:" + email);
    }

    public void checkMail(String ip) {
        if (count(mails, ip, Duration.ofHours(1)) >= mailsPerHour) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Anfragen, bitte später noch einmal");
        }
        add(mails, ip);
    }

    private int count(Map<String, Deque<Instant>> map, String key, Duration window) {
        Deque<Instant> q = map.get(key);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            Instant limit = clock.instant().minus(window);
            while (!q.isEmpty() && q.peekFirst().isBefore(limit)) {
                q.pollFirst();
            }
            return q.size();
        }
    }

    private void add(Map<String, Deque<Instant>> map, String key) {
        Deque<Instant> q = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(clock.instant());
        }
    }
}
