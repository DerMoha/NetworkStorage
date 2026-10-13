package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.NetworkScanStatus;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns scan jobs, content invalidation and publication. World access stays on the primary thread. */
final class NetworkScanCoordinator {
    private final BooleanSupplier primaryThread;
    private final ToIntFunction<Runnable> schedule;
    private final IntConsumer cancelTask;
    private final Logger logger;
    private final NetworkContentScanner contentScanner;
    private final Map<Network, ScanJob> scanJobs = new IdentityHashMap<>();
    private final Set<Network> attachedNetworks = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean scansCancelled;

    NetworkScanCoordinator(NetworkStoragePlugin plugin) {
        this(new NetworkContentScanner(), Bukkit::isPrimaryThread,
                task -> plugin.getServer().getScheduler().runTask(plugin, task).getTaskId(),
                id -> plugin.getServer().getScheduler().cancelTask(id), plugin.getLogger());
    }

    NetworkScanCoordinator(NetworkContentScanner scanner, BooleanSupplier primaryThread,
                           ToIntFunction<Runnable> schedule, IntConsumer cancelTask, Logger logger) {
        this.contentScanner = scanner;
        this.primaryThread = primaryThread;
        this.schedule = schedule;
        this.cancelTask = cancelTask;
        this.logger = logger;
    }

    private void requirePrimaryThread() {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Network storage scans and Bukkit world access must run on the main thread");
        }
    }

    /**
     * Connects a network's content mutations to the coalesced main-thread
     * scanner.  The callback deliberately does not read Bukkit state; it only
     * schedules the scan if a caller ever mutates a network off-thread.
     */
    void attachNetwork(Network network) {
        if (network == null || !attachedNetworks.add(network)) {
            return;
        }
        network.setContentChangeListener(() -> {
            if (primaryThread.getAsBoolean()) {
                getNetworkScan(network, false, null);
            } else {
                schedule.applyAsInt(() -> getNetworkScan(network, false, null));
            }
        });
    }

    /**
     * Returns the latest authoritative snapshot, starting a scan when the
     * snapshot is missing or stale.  A refresh always starts a fresh scan;
     * otherwise an existing scan is coalesced and allowed to finish.
     */
    public NetworkScanResult getNetworkScan(Network network,
                                            boolean refresh,
                                            Consumer<NetworkScanResult> completionCallback) {
        requirePrimaryThread();
        if (network == null) {
            return null;
        }
        attachNetwork(network);

        ScanJob existing = scanJobs.get(network);
        if (refresh) {
            if (existing != null) {
                restartScan(existing, completionCallback);
            } else {
                startScan(network, completionCallback);
            }
            return network.getScanResult();
        }

        NetworkScanResult current = network.getScanResult();
        if (current.status() == NetworkScanStatus.COMPLETE) {
            if (completionCallback != null) {
                completionCallback.accept(current);
            }
            return current;
        }

        // An incomplete result is an actionable diagnostic, not a request to
        // retry forever on every terminal render.  Content invalidation and
        // the explicit admin/terminal refresh path both create a fresh scan.
        if (current.status() == NetworkScanStatus.INCOMPLETE && existing == null) {
            return current;
        }

        if (existing == null) {
            startScan(network, completionCallback);
        } else if (completionCallback != null) {
            if (!existing.callbacks().contains(completionCallback)) {
                existing.callbacks().add(completionCallback);
            }
        }
        return network.getScanResult();
    }

    private void startScan(Network network, Consumer<NetworkScanResult> callback) {
        requirePrimaryThread();
        if (scansCancelled) {
            return;
        }
        NetworkContentScanner.ScanSession session = contentScanner.begin(
                network.getName(), network.getChestLocations(), network.getUnloadedChestLocations());
        network.beginScan(session.registeredLocations(), session.uniqueChunks());
        ScanJob job = new ScanJob(network, session, network.getContentVersion());
        if (callback != null) {
            job.callbacks().add(callback);
        }
        scanJobs.put(network, job);
        scheduleScanStep(job);
    }

    private void restartScan(ScanJob job, Consumer<NetworkScanResult> callback) {
        requirePrimaryThread();
        List<Consumer<NetworkScanResult>> callbacks = new ArrayList<>(job.callbacks());
        if (callback != null) {
            callbacks.add(callback);
        }
        cancelScan(job.network());
        startScan(job.network(), null);
        ScanJob replacement = scanJobs.get(job.network());
        if (replacement != null) {
            replacement.callbacks().addAll(callbacks);
        }
    }

    private void scheduleScanStep(ScanJob job) {
        job.taskId = schedule.applyAsInt(() -> advanceScan(job));
    }

    private void advanceScan(ScanJob job) {
        requirePrimaryThread();
        if (scanJobs.get(job.network()) != job || scansCancelled) {
            return;
        }
        if (job.network().getContentVersion() != job.contentVersion()) {
            restartScan(job, null);
            return;
        }

        NetworkContentScanner.ScanStep step = contentScanner.advance(
                job.session(), NetworkContentScanner.DEFAULT_CHUNKS_PER_TICK);
        if (!step.complete()) {
            scheduleScanStep(job);
            return;
        }

        if (job.network().getContentVersion() != job.contentVersion()) {
            restartScan(job, null);
            return;
        }

        NetworkScanResult result = step.result();
        job.network().applyScanResult(result);
        scanJobs.remove(job.network());
        job.taskId = -1;
        logScan(job.network(), job.session(), job.network().getScanResult());
        for (Consumer<NetworkScanResult> callback : List.copyOf(job.callbacks())) {
            try {
                callback.accept(job.network().getScanResult());
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING,
                        "NetworkStorage scan completion callback failed for " + job.network().getName(),
                        exception);
            }
        }
    }

    private void logScan(Network network,
                         NetworkContentScanner.ScanSession session,
                         NetworkScanResult result) {
        if (result.status() == NetworkScanStatus.COMPLETE) {
            logger.info(String.format(Locale.ROOT,
                    "%s scan: %,d registered locations, %,d containers found, %,d items",
                    network.getName(),
                    session.registeredLocations(),
                    session.containersFound(),
                    result.totalItems()));
            return;
        }

        logger.warning(network.getName() + " scan " + result.status()
                + ": " + session.containersFound() + "/" + session.registeredLocations()
                + " containers found; item total is not authoritative.");
        for (String warning : result.warnings()) {
            logger.warning(warning);
        }
    }

    private void cancelScan(Network network) {
        ScanJob job = scanJobs.remove(network);
        if (job != null && job.taskId != -1) {
            cancelTask.accept(job.taskId);
            job.taskId = -1;
        }
    }

    void detachNetwork(Network network) {
        cancelScan(network);
        attachedNetworks.remove(network);
        network.setContentChangeListener(null);
    }

    public void cancelScheduledScans() {
        requirePrimaryThread();
        scansCancelled = true;
        for (ScanJob job : new ArrayList<>(scanJobs.values())) {
            if (job.taskId != -1) {
                cancelTask.accept(job.taskId);
            }
        }
        scanJobs.clear();
    }

    private static final class ScanJob {
        private final Network network;
        private final NetworkContentScanner.ScanSession session;
        private final long contentVersion;
        private final List<Consumer<NetworkScanResult>> callbacks = new ArrayList<>();
        private int taskId = -1;

        private ScanJob(Network network, NetworkContentScanner.ScanSession session, long contentVersion) {
            this.network = network;
            this.session = session;
            this.contentVersion = contentVersion;
        }

        private Network network() {
            return network;
        }

        private NetworkContentScanner.ScanSession session() {
            return session;
        }

        private long contentVersion() {
            return contentVersion;
        }

        private List<Consumer<NetworkScanResult>> callbacks() {
            return callbacks;
        }
    }

}
