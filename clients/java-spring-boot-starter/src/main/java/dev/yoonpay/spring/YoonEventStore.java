package dev.yoonpay.spring;

/**
 * Where handled event ids are remembered. The default is {@link InMemoryYoonEventStore}; declare
 * your own bean (e.g. {@link CacheYoonEventStore} over a shared cache) when you run more than one
 * instance.
 */
public interface YoonEventStore {

    boolean has(String eventId);

    void add(String eventId);
}
