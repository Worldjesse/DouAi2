package com.douai.module.feature;

import java.util.ArrayList;
import java.util.List;
import java.net.URI;

/** Pure, fail-open filtering for an upstream feed response list. */
public final class FeedResponseFilter {
    private FeedResponseFilter() { }

    public static boolean isMainFeedUrl(String url) {
        if (url == null) return false;
        try {
            String path = URI.create(url).getPath();
            return isEndpoint(path, "/aweme/v1/feed/")
                    || isEndpoint(path, "/aweme/v2/feed/");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isEndpoint(String path, String endpoint) {
        return path != null && (path.equals(endpoint.substring(0, endpoint.length() - 1))
                || path.equals(endpoint));
    }

    /**
     * Returns a new ordered list when a non-empty subset remains; otherwise preserves the input.
     * If classification fails for any item, the whole page is passed through unchanged.
     */
    public static <T> List<T> filter(List<T> source, boolean enabled, ItemFilter<T> filter) {
        return filter(source, enabled, filter, false);
    }

    static <T> List<T> filter(List<T> source, boolean enabled, ItemFilter<T> filter,
                             boolean allowEmpty) {
        if (!enabled || source == null || source.isEmpty() || filter == null) return source;
        ArrayList<T> kept = new ArrayList<>(source.size());
        try {
            for (T item : source) {
                if (item == null || !filter.shouldHide(item)) kept.add(item);
            }
        } catch (Exception ignored) {
            return source;
        }
        if ((!allowEmpty && kept.isEmpty()) || kept.size() == source.size()) return source;
        return kept;
    }

    @FunctionalInterface
    public interface ItemFilter<T> {
        boolean shouldHide(T item) throws Exception;
    }
}
