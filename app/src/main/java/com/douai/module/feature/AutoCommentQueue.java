package com.douai.module.feature;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

/** Serializes captured comment targets and ignores callbacks from obsolete attempts. */
final class AutoCommentQueue<T> implements AutoCloseable {
    interface Scheduler {
        void post(Runnable action);
        void later(Runnable action, long delayMillis);
    }
    interface Processor<T> { void run(T target, int attempt, Consumer<String> result); }
    interface Observer<T> { void finished(T target, String status, int attempts); }
    interface StateObserver<T> { void changed(T current, int count, int attempts, String phase); }
    interface RetryPolicy<T> { boolean retry(T target, int attempts); }
    private static final int MAX_PENDING = 32;
    private final Scheduler scheduler;
    private final Processor<T> processor;
    private final Observer<T> observer;
    private final StateObserver<T> stateObserver;
    private final RetryPolicy<T> retryPolicy;
    private final ArrayDeque<Job<T>> jobs = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private long epoch;
    private boolean closed;

    AutoCommentQueue(Scheduler scheduler, Processor<T> processor, Observer<T> observer) {
        this(scheduler, processor, observer, (current, count, attempts, phase) -> { });
    }

    AutoCommentQueue(Scheduler scheduler, Processor<T> processor, Observer<T> observer, StateObserver<T> stateObserver) {
        this(scheduler, processor, observer, stateObserver, (target, attempts) -> attempts < 3);
    }

    AutoCommentQueue(Scheduler scheduler, Processor<T> processor, Observer<T> observer,
                     StateObserver<T> stateObserver, RetryPolicy<T> retryPolicy) {
        this.scheduler = scheduler;
        this.processor = processor;
        this.observer = observer;
        this.stateObserver = stateObserver;
        this.retryPolicy = retryPolicy;
    }

    void offer(String identity, T target, long delayMillis) {
        scheduler.post(() -> {
            if (closed || seen.contains(identity)) return;
            if (jobs.size() >= MAX_PENDING) { observer.finished(target, "failed", 0); return; }
            seen.add(identity);
            while (seen.size() > 256) seen.remove(seen.iterator().next());
            jobs.add(new Job<>(target, Math.max(0, delayMillis)));
            if (jobs.size() == 1) schedule(jobs.peek(), jobs.peek().delay);
            else notifyState();
        });
    }

    private void schedule(Job<T> job, long delay) {
        long expected = epoch;
        job.phase = job.attempts == 0 ? "waiting" : "retrying";
        notifyState();
        scheduler.later(() -> {
            if (closed || epoch != expected || jobs.peek() != job) return;
            int attempt = ++job.attempts;
            job.phase = "running";
            notifyState();
            try {
                processor.run(job.target, attempt, result -> scheduler.post(() -> {
                    if (closed || epoch != expected || jobs.peek() != job || job.attempts != attempt || job.completedAttempt == attempt) return;
                    job.completedAttempt = attempt;
                    if ("failed".equals(result) && retryPolicy.retry(job.target, attempt)) {
                        schedule(job, retryDelay(attempt)); return;
                    }
                    String status = "sent".equals(result) || "submitted".equals(result) || "cancelled".equals(result) ? result : "failed";
                    jobs.remove();
                    observer.finished(job.target, status, attempt);
                    if (!jobs.isEmpty()) schedule(jobs.peek(), jobs.peek().delay);
                    else notifyState();
                }));
            } catch (RuntimeException failure) {
                scheduler.post(() -> failSynchronously(job, expected, attempt));
            }
        }, delay);
    }

    private void failSynchronously(Job<T> job, long expected, int attempt) {
        if (closed || epoch != expected || jobs.peek() != job || job.completedAttempt == attempt) return;
        job.completedAttempt = attempt;
        if (retryPolicy.retry(job.target, attempt)) schedule(job, retryDelay(attempt));
        else {
            jobs.remove();
            observer.finished(job.target, "failed", attempt);
            if (!jobs.isEmpty()) schedule(jobs.peek(), jobs.peek().delay);
            else notifyState();
        }
    }

    private static long retryDelay(int attempts) { return ((attempts - 1) % 3 + 1) * 1000L; }

    void reset() {
        scheduler.post(() -> {
            epoch++;
            while (!jobs.isEmpty()) {
                Job<T> job = jobs.remove();
                observer.finished(job.target, "cancelled", job.attempts);
            }
            seen.clear();
            notifyState();
        });
    }

    void cancel(T target) {
        scheduler.post(() -> {
            Job<T> selected = null;
            for (Job<T> job : jobs) if (job.target == target) { selected = job; break; }
            if (selected == null || closed) return;
            cancel(selected);
        });
    }

    void cancelLatest(Consumer<T> beforeCancel) {
        scheduler.post(() -> {
            if (closed || jobs.isEmpty()) return;
            Job<T> selected = jobs.peekLast();
            if (beforeCancel != null) beforeCancel.accept(selected.target);
            cancel(selected);
        });
    }

    private void cancel(Job<T> selected) {
        boolean current = jobs.peek() == selected;
        jobs.remove(selected);
        observer.finished(selected.target, "cancelled", selected.attempts);
        if (current && !jobs.isEmpty()) schedule(jobs.peek(), jobs.peek().delay);
        else notifyState();
    }

    private void notifyState() {
        Job<T> current = jobs.peek();
        stateObserver.changed(current == null ? null : current.target, jobs.size(), current == null ? 0 : current.attempts,
                current == null ? "empty" : current.phase);
    }

    @Override public void close() {
        scheduler.post(() -> { closed = true; epoch++; jobs.clear(); seen.clear(); notifyState(); });
    }

    private static final class Job<T> {
        final T target;
        final long delay;
        int attempts;
        int completedAttempt;
        String phase = "waiting";
        Job(T target, long delay) { this.target = target; this.delay = delay; }
    }
}
