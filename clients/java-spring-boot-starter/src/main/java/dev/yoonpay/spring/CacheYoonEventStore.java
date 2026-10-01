package dev.yoonpay.spring;

import org.springframework.cache.Cache;

/**
 * Remembers event ids in a Spring {@link Cache} (Redis, Caffeine, …). The cache's own
 * configuration decides how long entries live: keep it longer than Yoon's retry schedule (days).
 *
 * <pre>{@code
 * @Bean
 * YoonEventStore yoonEventStore(CacheManager caches) {
 *     return new CacheYoonEventStore(caches.getCache("yoon-events"));
 * }
 * }</pre>
 */
public final class CacheYoonEventStore implements YoonEventStore {

    private final Cache cache;

    public CacheYoonEventStore(Cache cache) {
        if (cache == null) {
            throw new IllegalArgumentException("cache is null: is it declared in your CacheManager?");
        }
        this.cache = cache;
    }

    @Override
    public boolean has(String eventId) {
        return cache.get(eventId) != null;
    }

    @Override
    public void add(String eventId) {
        cache.put(eventId, Boolean.TRUE);
    }
}
