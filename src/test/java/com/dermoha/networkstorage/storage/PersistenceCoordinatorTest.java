package com.dermoha.networkstorage.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceCoordinatorTest {

    private static final StorageSnapshot EMPTY = new StorageSnapshot(java.util.List.of(), Map.of(), Map.of());

    @Test
    void reportsPendingThenHealthyAfterAsyncCommit() throws Exception {
        FakeProvider provider = new FakeProvider(true);
        try (PersistenceCoordinator coordinator = new PersistenceCoordinator(
                provider, Logger.getAnonymousLogger(), 25)) {
            coordinator.request(EMPTY);
            assertEquals(PersistenceCoordinator.Status.PENDING, coordinator.status());
            assertTrue(provider.saved.await(2, TimeUnit.SECONDS));
            awaitStatus(coordinator, PersistenceCoordinator.Status.HEALTHY);
        }
    }

    @Test
    void reportsDegradedAfterFailedCommit() throws Exception {
        FakeProvider provider = new FakeProvider(false);
        try (PersistenceCoordinator coordinator = new PersistenceCoordinator(
                provider, Logger.getAnonymousLogger(), 0)) {
            coordinator.request(EMPTY);
            assertTrue(provider.saved.await(2, TimeUnit.SECONDS));
            awaitStatus(coordinator, PersistenceCoordinator.Status.DEGRADED);
            assertEquals("simulated failure", coordinator.lastError());
        }
    }

    private void awaitStatus(PersistenceCoordinator coordinator,
                             PersistenceCoordinator.Status expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (coordinator.status() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, coordinator.status());
    }

    private static final class FakeProvider implements NetworkStorageProvider {
        private final boolean result;
        private final CountDownLatch saved = new CountDownLatch(1);

        private FakeProvider(boolean result) {
            this.result = result;
        }

        @Override public boolean saveSnapshot(StorageSnapshot snapshot) {
            saved.countDown();
            return result;
        }

        @Override public Map<String, Object> snapshot() {
            return Map.of("last_error", "simulated failure");
        }

        @Override public void initialize() {}
        @Override public void shutdown() {}
        @Override public String getBackendName() { return "FAKE"; }
        @Override public int getSchemaVersion() { return 1; }
        @Override public boolean isEmpty() { return true; }
        @Override public boolean isAvailable() { return true; }
        @Override public void loadNetworks(Map<String, Network> networks,
                                           Map<UUID, String> selectedNetworks,
                                           Map<UUID, String> selectedWirelessNetworks) {}
        @Override public boolean saveSnapshot(Collection<Network> networks,
                                              Map<UUID, String> selectedNetworks,
                                              Map<UUID, String> selectedWirelessNetworks) {
            return result;
        }
    }
}
