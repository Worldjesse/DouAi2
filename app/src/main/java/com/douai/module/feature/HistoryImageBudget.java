package com.douai.module.feature;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;

/** Admission for retained in-memory history images, separate from transient media processing. */
final class HistoryImageBudget {
    static final long CAPACITY_BYTES = 48L * 1024 * 1024;
    static final HistoryImageBudget SHARED = new HistoryImageBudget(CAPACITY_BYTES);
    private final long capacity;
    private final LinkedHashSet<Ticket> oldestFirst = new LinkedHashSet<>();
    private final ReferenceQueue<ChatCompletionClient> collectedOwners = new ReferenceQueue<>();
    private final ReferenceQueue<Object> collectedTurns = new ReferenceQueue<>();
    private long used;

    HistoryImageBudget(long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid history image capacity");
        this.capacity = capacity;
    }

    /** Callbacks only mutate history under this monitor; they never acquire another client's request lock. */
    synchronized Ticket retain(ChatCompletionClient owner, Object turn, long bytes) {
        reap();
        if (bytes <= 0) return null;
        if (bytes > capacity) {
            owner.discardBudgetedTurn(turn);
            return null;
        }
        while (used > capacity - bytes) {
            Ticket oldest = oldestFirst.iterator().next();
            ChatCompletionClient previousOwner = oldest.get();
            Object previousTurn = oldest.turn.get();
            release(oldest);
            if (previousOwner != null && previousTurn != null) previousOwner.discardBudgetedTurn(previousTurn);
        }
        Ticket ticket = new Ticket(owner, turn, bytes, collectedOwners, collectedTurns);
        oldestFirst.add(ticket);
        used += bytes;
        return ticket;
    }

    synchronized void release(Ticket ticket) {
        if (ticket == null || ticket.released) return;
        ticket.released = true;
        oldestFirst.remove(ticket);
        used -= ticket.bytes;
        ticket.clear();
        ticket.turn.clear();
    }

    synchronized long usedBytes() { reap(); return used; }
    synchronized int retainedTurns() { reap(); return oldestFirst.size(); }

    private void reap() {
        Ticket owner;
        while ((owner = (Ticket) collectedOwners.poll()) != null) release(owner);
        TurnReference turn;
        while ((turn = (TurnReference) collectedTurns.poll()) != null) release(turn.ticket);
    }

    static final class Ticket extends WeakReference<ChatCompletionClient> {
        final long bytes;
        final TurnReference turn;
        boolean released;
        Ticket(ChatCompletionClient owner, Object value, long bytes,
               ReferenceQueue<ChatCompletionClient> owners, ReferenceQueue<Object> turns) {
            super(owner, owners);
            this.bytes = bytes;
            turn = new TurnReference(value, turns, this);
        }
    }

    private static final class TurnReference extends WeakReference<Object> {
        final Ticket ticket;
        TurnReference(Object value, ReferenceQueue<Object> queue, Ticket ticket) {
            super(value, queue);
            this.ticket = ticket;
        }
    }
}
