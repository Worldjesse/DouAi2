package com.douai.module.feature;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One user gesture. Kept after claiming so a deferred native confirmation cannot send text. */
final class HashVoiceGesture {
    final String draft;
    final String group;
    final String service;
    final String reply;
    private final BooleanSupplier current;
    private final BooleanSupplier sendable;
    private final Runnable restore;
    private boolean claimed;
    private boolean cancelled;

    HashVoiceGesture(String draft, String group, String service, String reply,
                     BooleanSupplier current, BooleanSupplier sendable, Runnable restore) {
        this.draft = Objects.requireNonNull(draft);
        this.group = Objects.requireNonNull(group);
        this.service = Objects.requireNonNull(service);
        this.reply = Objects.requireNonNull(reply);
        this.current = current;
        this.sendable = sendable;
        this.restore = restore;
    }

    boolean matches(String text, String group, String service, String reply) {
        return draft.equals(text) && this.group.equals(group)
                && this.service.equals(service) && this.reply.equals(reply);
    }

    synchronized boolean claim() {
        if (claimed || cancelled || !isCurrent()) return false;
        claimed = true;
        return true;
    }

    synchronized void cancel() { cancelled = true; }

    synchronized boolean canRetry() { return cancelled; }

    synchronized boolean isCurrent() {
        if (cancelled) return false;
        try { return current.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    synchronized boolean canSend() {
        if (!isCurrent()) return false;
        try { return sendable.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    void restoreAfterNativeClick() { restore.run(); }
}
