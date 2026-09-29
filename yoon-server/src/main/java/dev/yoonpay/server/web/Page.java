package dev.yoonpay.server.web;

import java.util.List;
import java.util.function.Function;

/**
 * A page of a cursor-paginated list, newest first. {@code nextCursor} is the id to pass as
 * {@code starting_after} for the next page.
 */
public record Page<T>(List<T> data, boolean hasMore, String nextCursor) {

    public static final int MAX_LIMIT = 100;

    /** Builds a page from {@code limit + 1} fetched rows. */
    public static <T> Page<T> of(List<T> fetched, int limit, Function<T, String> id) {
        boolean more = fetched.size() > limit;
        List<T> data = more ? fetched.subList(0, limit) : fetched;
        return new Page<>(List.copyOf(data), more, more ? id.apply(data.getLast()) : null);
    }

    public static int limit(Integer requested) {
        if (requested == null) {
            return 20;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw ApiProblem.invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(data.stream().map(f).toList(), hasMore, nextCursor);
    }
}
