package com.dermoha.networkstorage.storage;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serializes detached SQLite snapshots without ever touching Bukkit from a worker. */
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
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile StorageSnapshot pending;
    private volatile boolean closed;
    private volatile boolean writing;
    private volatile long lastSuccessAt;
    private volatile String lastError = "";
    private volatile long retryMs = 1_000L;
    private final long debounceMs;

    public PersistenceCoordinator(NetworkStorageProvider provider, Logger logger, long debounceMs) {
        this.provider = provider;
        this.logger = logger;
        this.debounceMs = debounceMs;
    }

    public void request(StorageSnapshot snapshot) {
        if (closed) return;
        pending = snapshot; // newest complete snapshot supersedes older queued work
        schedule(debounceMs);
    }

    private void schedule(long delayMs) {
        if (!scheduled.compareAndSet(false, true) || closed) return;
        worker.schedule(this::writeLatest, delayMs, TimeUnit.MILLISECONDS);
    }

    private void writeLatest() {
        scheduled.set(false);
        StorageSnapshot snapshot = pending;
        if (snapshot == null || closed) return;
        writing = true;
        boolean saved = provider.saveSnapshot(snapshot);
        writing = false;
        if (saved) {
            if (pending == snapshot) pending = null;
            lastSuccessAt = System.currentTimeMillis();
            lastError = "";
            retryMs = 1_000L;
            if (pending != null) schedule(debounceMs);
            return;
        }
        lastError = String.valueOf(provider.snapshot().getOrDefault("last_error", "SQLite save failed"));
        logger.log(Level.WARNING, "SQLite save failed; retrying in " + retryMs + "ms: " + lastError);
        long delay = retryMs;
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
        schedule(delay);
    }

    public boolean flush(Duration timeout) {
        if (closed) return pending == null;
        StorageSnapshot snapshot = pending;
        if (snapshot != null) {
            pending = null;
            try {
                return worker.submit(() -> provider.saveSnapshot(snapshot))
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return false;
            }
        }
        return !writing;
    }

    public long lastSuccessAt() { return lastSuccessAt; }
    public String lastError() { return lastError; }
    public boolean isPending() { return pending != null || writing; }
    public Status status() {
        if (!lastError.isBlank()) return Status.DEGRADED;
        return isPending() ? Status.PENDING : Status.HEALTHY;
    }

    @Override public void close() {
        closed = true;
        worker.shutdown();
        try { worker.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
