package dev.yoonpay.spring;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process store with a time-to-live. It only knows its own process. */
public final class InMemoryYoonEventStore implements YoonEventStore {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlNanos;

    public InMemoryYoonEventStore(Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    @Override
    public boolean has(String eventId) {
        Long at = seen.get(eventId);
        if (at == null) {
            return false;
        }
        if (System.nanoTime() - at > ttlNanos) {
            seen.remove(eventId, at);
            return false;
        }
        return true;
    }

    @Override
    public void add(String eventId) {
        long now = System.nanoTime();
        if (seen.size() >= MAX_ENTRIES) {
            seen.entrySet().removeIf(e -> now - e.getValue() > ttlNanos);
        }
        seen.put(eventId, now);
    }
}
