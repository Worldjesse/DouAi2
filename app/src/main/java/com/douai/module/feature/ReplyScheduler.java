package com.douai.module.feature;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded, single-consumer queue that preserves order within each conversation. */
final class ReplyScheduler<T> {
    private final int capacity;
    private final LinkedHashMap<String, ArrayDeque<Item<T>>> conversations = new LinkedHashMap<>();
    private int size;

    ReplyScheduler(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid reply queue capacity");
        this.capacity = capacity;
    }

    synchronized boolean offer(String conversation, T value, long readyAt) {
        if (conversation == null || value == null || readyAt < 0) throw new IllegalArgumentException("Invalid reply");
        if (size >= capacity) return false;
        conversations.computeIfAbsent(conversation, ignored -> new ArrayDeque<>()).addLast(new Item<>(value, readyAt));
        size++;
        return true;
    }

    /** Take one ready conversation, then rotate it behind the other conversations. */
    synchronized T poll(long now) {
        Iterator<Map.Entry<String, ArrayDeque<Item<T>>>> iterator = conversations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ArrayDeque<Item<T>>> entry = iterator.next();
            ArrayDeque<Item<T>> queue = entry.getValue();
            if (queue.peekFirst().readyAt > now) continue;
            T value = queue.removeFirst().value;
            String conversation = entry.getKey();
            iterator.remove();
            if (!queue.isEmpty()) conversations.put(conversation, queue);
            size--;
            return value;
        }
        return null;
    }

    synchronized long nextReadyAt() {
        long earliest = Long.MAX_VALUE;
        for (ArrayDeque<Item<T>> queue : conversations.values()) earliest = Math.min(earliest, queue.peekFirst().readyAt);
        return earliest;
    }

    synchronized boolean remove(T value) {
        Iterator<ArrayDeque<Item<T>>> iterator = conversations.values().iterator();
        while (iterator.hasNext()) {
            ArrayDeque<Item<T>> queue = iterator.next();
            if (!queue.removeIf(item -> item.value == value)) continue;
            if (queue.isEmpty()) iterator.remove();
            size--;
            return true;
        }
        return false;
    }

    synchronized int size() { return size; }

    private static final class Item<T> {
        final T value;
        final long readyAt;
        Item(T value, long readyAt) { this.value = value; this.readyAt = readyAt; }
    }
}
