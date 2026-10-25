package com.douai.module.feature;

/** Process-wide admission for transient media allocations. Reservations follow resource ownership. */
public final class MediaMemoryBudget {
    public static final long CAPACITY_BYTES = 64L * 1024 * 1024;
    private static final Pool SHARED = new Pool(CAPACITY_BYTES);

    private MediaMemoryBudget() { }

    /** Visual effects must fall back immediately when memory is already in use. */
    public static Reservation tryAcquire(long bytes) { return SHARED.tryAcquire(bytes); }

    /** Media workers wait with cancellation rather than allocating above the process budget. */
    static Reservation acquire(long bytes, MediaReadCancellation cancellation) throws Exception {
        return SHARED.acquire(bytes, cancellation);
    }

    static final class Pool {
        final long capacity;
        private long used;

        Pool(long capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Invalid media capacity");
            this.capacity = capacity;
        }

        synchronized Reservation tryAcquire(long bytes) {
            if (bytes <= 0 || bytes > capacity || bytes > capacity - used) return null;
            used += bytes;
            return new Reservation(this, bytes);
        }

        Reservation acquire(long bytes, MediaReadCancellation cancellation) throws Exception {
            if (bytes <= 0 || bytes > capacity) throw new IllegalArgumentException("Media allocation exceeds budget");
            while (true) {
                ChatMediaLoader.checkCancelled(cancellation);
                synchronized (this) {
                    Reservation reservation = tryAcquire(bytes);
                    if (reservation != null) return reservation;
                    try { wait(50); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException("媒体处理已取消");
                    }
                }
            }
        }

        synchronized long usedBytes() { return used; }

        synchronized void release(long bytes) { used -= bytes; notifyAll(); }
    }

    public static final class Reservation implements AutoCloseable {
        private Pool owner;
        public final long bytes;

        private Reservation(Pool owner, long bytes) { this.owner = owner; this.bytes = bytes; }

        @Override public synchronized void close() {
            if (owner == null) return;
            Pool previous = owner;
            owner = null;
            previous.release(bytes);
        }
    }
}
