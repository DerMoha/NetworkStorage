package com.dermoha.networkstorage.storage;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceCoordinatorTest {
    private static final StorageSnapshot EMPTY = new StorageSnapshot(List.of(), Map.of(), Map.of(), Map.of());
    private interface Save { boolean run(StorageSnapshot snapshot); }
    private NetworkStorageProvider provider(Save save, boolean brokenDiagnostics) {
        return (NetworkStorageProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NetworkStorageProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("saveSnapshot")) return save.run((StorageSnapshot) args[0]);
                    if (method.getName().equals("snapshot")) {
                        if (brokenDiagnostics) throw new IllegalStateException("diagnostic failure");
                        return Map.of("last_error", "write failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
    @Test void failureKeepsPendingAndRecoveryAcknowledges() {
        AtomicBoolean success = new AtomicBoolean();
        try (var coordinator = new PersistenceCoordinator(provider(s -> success.get(), false), Logger.getAnonymousLogger(), 10000)) {
            coordinator.request(EMPTY);
            assertFalse(coordinator.flush(Duration.ofMillis(100)));
            assertTrue(coordinator.isPending());
            assertEquals(PersistenceCoordinator.Status.DEGRADED, coordinator.status());
            success.set(true);
            assertTrue(coordinator.flush(Duration.ofSeconds(2)));
            assertFalse(coordinator.isPending());
            assertTrue(coordinator.lastSuccessAt() > 0);
            assertEquals("", coordinator.lastError());
        }
    }
    @Test void unexpectedSaveAndDiagnosticFailuresRemainRetryable() {
        for (boolean diagnostic : new boolean[]{false, true}) {
            AtomicBoolean success = new AtomicBoolean();
            try (var coordinator = new PersistenceCoordinator(provider(s -> {
                if (success.get()) return true;
                if (!diagnostic) throw new IllegalStateException("adapter failure");
                return false;
            }, diagnostic), Logger.getAnonymousLogger(), 10000)) {
                coordinator.request(EMPTY);
                assertFalse(coordinator.flush(Duration.ofMillis(100)));
                assertEquals(PersistenceCoordinator.Status.DEGRADED, coordinator.status());
                success.set(true);
                assertTrue(coordinator.flush(Duration.ofSeconds(2)));
            }
        }
    }
    @Test void timedOutWriteCannotClearNewerRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var coordinator = new PersistenceCoordinator(provider(s -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { throw new RuntimeException(exception); }
            }
            return true;
        }, false), Logger.getAnonymousLogger(), 0)) {
            coordinator.request(EMPTY);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(coordinator.flush(Duration.ofMillis(30)));
            coordinator.request(EMPTY);
            assertTrue(coordinator.isPending());
            release.countDown();
            assertTrue(coordinator.flush(Duration.ofSeconds(2)));
            assertEquals(2, calls.get());
            assertFalse(coordinator.isPending());
        } finally { release.countDown(); }
    }
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
                                           Map<UUID, String> selectedWirelessNetworks,
                                           Map<UUID, String> sortTypes) {}
        @Override public boolean saveSnapshot(Collection<Network> networks,
                                              Map<UUID, String> selectedNetworks,
                                              Map<UUID, String> selectedWirelessNetworks,
                                              Map<UUID, String> sortTypes) {
            return result;
        }
    }
}
