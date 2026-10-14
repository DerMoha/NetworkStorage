package com.dermoha.networkstorage.storage;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serializes detached snapshots; only an acknowledged revision may leave the queue. */
public final class PersistenceCoordinator implements AutoCloseable {
    public enum Status { HEALTHY, PENDING, DEGRADED }
    private static final long MAX_RETRY_MS = 30_000L;
    private final NetworkStorageProvider provider;
    private final Logger logger;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "NetworkStorage-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> scheduled;
    private StorageSnapshot pending;
    private long requestedRevision;
    private long savedRevision;
    private boolean closed;
    private boolean writing;
    private long lastSuccessAt;
    private String lastError = "";
    private long retryMs = 1_000L;
    private final long debounceMs;

    public PersistenceCoordinator(NetworkStorageProvider provider, Logger logger, long debounceMs) {
        this.provider = provider;
        this.logger = logger;
        this.debounceMs = Math.max(0, debounceMs);
    }

    public synchronized void request(StorageSnapshot snapshot) {
        if (closed) return;
        pending = java.util.Objects.requireNonNull(snapshot);
        requestedRevision++;
        schedule(debounceMs);
    }

    private void schedule(long delayMs) {
        if (closed || writing || scheduled != null || pending == null) return;
        scheduled = worker.schedule(this::writeLatest, delayMs, TimeUnit.MILLISECONDS);
    }

    private void writeLatest() {
        StorageSnapshot snapshot;
        long revision;
        synchronized (this) {
            scheduled = null;
            if (pending == null || closed) return;
            snapshot = pending;
            revision = requestedRevision;
            writing = true;
        }
        boolean saved = false;
        String error = "SQLite save failed";
        try {
            saved = provider.saveSnapshot(snapshot);
            if (!saved) error = String.valueOf(provider.snapshot().getOrDefault("last_error", error));
        } catch (RuntimeException exception) {
            error = exception.toString();
        } finally {
            synchronized (this) {
                writing = false;
                if (saved) {
                    savedRevision = revision;
                    if (requestedRevision == revision) pending = null;
                    lastSuccessAt = System.currentTimeMillis();
                    lastError = "";
                    retryMs = 1_000L;
                    schedule(debounceMs);
                } else {
                    lastError = error.isBlank() ? "SQLite save failed" : error;
                    logger.log(Level.WARNING, "SQLite save failed; retrying in " + retryMs + "ms: " + lastError);
                    long delay = retryMs;
                    retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
                    schedule(delay);
                }
                notifyAll();
            }
        }
    }

    /** Waits for the revision present at entry, including any write already in flight. */
    public synchronized boolean flush(Duration timeout) {
        long target = requestedRevision;
        if (savedRevision >= target) return true;
        if (closed) return false;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        schedule(0);
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        while (savedRevision < target && remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastError = "Interrupted while waiting for SQLite save";
                return false;
            }
            remaining = deadline - System.nanoTime();
        }
        if (savedRevision >= target) return true;
        if (lastError.isBlank()) lastError = "Timed out waiting for SQLite save";
        return false;
    }

    public synchronized long lastSuccessAt() { return lastSuccessAt; }
    public synchronized String lastError() { return lastError; }
    public synchronized boolean isPending() { return pending != null || writing; }
    public synchronized Status status() {
        if (!lastError.isBlank()) return Status.DEGRADED;
        return isPending() ? Status.PENDING : Status.HEALTHY;
    }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            if (scheduled != null) scheduled.cancel(false);
            worker.shutdown();
        }
        try { worker.awaitTermination(30, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
