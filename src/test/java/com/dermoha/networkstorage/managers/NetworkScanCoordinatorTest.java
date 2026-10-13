package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.NetworkScanStatus;
import com.dermoha.networkstorage.storage.StoredLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NetworkScanCoordinatorTest {
    private final Map<Integer, Runnable> tasks = new LinkedHashMap<>();
    private final AtomicBoolean primaryThread = new AtomicBoolean(true);
    private int nextId;
    private final NetworkScanCoordinator scans = new NetworkScanCoordinator(
            new NetworkContentScanner(primaryThread::get), primaryThread::get,
            task -> { tasks.put(++nextId, task); return nextId; }, tasks::remove,
            Logger.getAnonymousLogger());
    private final Network network = new Network("Test", UUID.randomUUID(), null);

    @Test
    void coalescesRequestsAndPublishesOncePerCallback() {
        List<NetworkScanResult> results = new ArrayList<>();
        Consumer<NetworkScanResult> callback = results::add;
        scans.getNetworkScan(network, false, callback);
        scans.getNetworkScan(network, false, callback);
        assertEquals(1, tasks.size());
        tick();
        assertEquals(1, results.size());
        assertSame(network.getScanResult(), results.getFirst());
        assertEquals(NetworkScanStatus.COMPLETE, results.getFirst().status());
        scans.getNetworkScan(network, false, callback);
        assertEquals(2, results.size(), "complete snapshots should be delivered immediately");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void refreshCancelsOldJobAndRetainsItsCallbacks() {
        List<NetworkScanResult> first = new ArrayList<>();
        List<NetworkScanResult> second = new ArrayList<>();
        scans.getNetworkScan(network, false, first::add);
        Runnable staleTask = tasks.values().iterator().next();
        scans.getNetworkScan(network, true, second::add);
        staleTask.run();
        assertTrue(first.isEmpty());
        assertEquals(1, tasks.size());
        tick();
        assertEquals(1, first.size());
        assertEquals(first, second);
    }

    @Test
    void contentVersionChangeRestartsWithoutPublishingStaleResult() {
        List<NetworkScanResult> results = new ArrayList<>();
        scans.getNetworkScan(network, false, results::add);
        network.invalidateItemCache();
        tick();
        assertTrue(results.isEmpty());
        assertEquals(1, tasks.size());
        tick();
        assertEquals(1, results.size());
        assertEquals(NetworkScanStatus.COMPLETE, results.getFirst().status());
    }

    @Test
    void incompleteResultWaitsForExplicitRefresh() {
        network.addUnloadedChest(new StoredLocation("missing", 0, 64, 0));
        scans.getNetworkScan(network, false, null);
        tick();
        assertEquals(NetworkScanStatus.INCOMPLETE, network.getScanResult().status());
        scans.getNetworkScan(network, false, ignored -> fail("incomplete scans do not retry"));
        assertTrue(tasks.isEmpty());
        scans.getNetworkScan(network, true, null);
        assertEquals(1, tasks.size());
        tick();
        assertEquals(NetworkScanStatus.INCOMPLETE, network.getScanResult().status());
    }

    @Test
    void detachCancelsJobAndDisconnectsInvalidation() {
        scans.getNetworkScan(network, false, ignored -> fail("detached callback"));
        Runnable staleTask = tasks.values().iterator().next();
        scans.detachNetwork(network);
        network.invalidateItemCache();
        staleTask.run();
        assertTrue(tasks.isEmpty());
    }

    @Test
    void shutdownPreventsScheduledAndFutureScans() {
        scans.getNetworkScan(network, false, ignored -> fail("shutdown callback"));
        Runnable staleTask = tasks.values().iterator().next();
        scans.cancelScheduledScans();
        staleTask.run();
        network.invalidateItemCache();
        scans.getNetworkScan(network, true, null);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void offThreadInvalidationOnlySchedulesUntilPrimaryThreadRuns() {
        scans.attachNetwork(network);
        primaryThread.set(false);
        network.invalidateItemCache();
        assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, () -> scans.getNetworkScan(network, false, null));
        primaryThread.set(true);
        tick();
        assertEquals(1, tasks.size());
        tick();
        assertEquals(NetworkScanStatus.COMPLETE, network.getScanResult().status());
    }

    @Test
    void failingCallbackDoesNotPreventRemainingCallbacks() {
        List<NetworkScanResult> results = new ArrayList<>();
        scans.getNetworkScan(network, false, ignored -> { throw new IllegalStateException("expected"); });
        scans.getNetworkScan(network, false, results::add);
        tick();
        assertEquals(1, results.size());
    }

    private void tick() {
        int id = tasks.keySet().iterator().next();
        tasks.remove(id).run();
    }
}
