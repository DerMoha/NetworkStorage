package com.dermoha.networkstorage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {
    @Test void concurrentChecksShareFetchAndRetirementSuppressesLateResult() throws Exception {
        java.util.List<Runnable> queue = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();
        UpdateChecker checker = new UpdateChecker("1", () -> { fetches.incrementAndGet(); return "2"; }, queue::add);
        var first = checker.checkNow();
        assertSame(first, java.util.concurrent.CompletableFuture.supplyAsync(checker::checkNow).get());
        assertEquals(1, queue.size());
        checker.close();
        queue.getFirst().run();
        assertEquals(UpdateChecker.Status.FAILURE, first.join().getStatus());
        assertNull(checker.getLastResult());
        assertFalse(checker.isUpdateAvailable());
    }

    @Test void failuresAreFailedOrStaleNeverLatest() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        java.util.concurrent.atomic.AtomicReference<String> response = new java.util.concurrent.atomic.AtomicReference<>();
        UpdateChecker checker = new UpdateChecker("1", response::get, queue::add);
        checker.checkNow(); queue.remove().run();
        assertEquals("failed", checker.getStatus());
        response.set("2");
        checker.checkNow(); queue.remove().run();
        assertEquals("outdated", checker.getStatus());
        response.set(null);
        checker.checkNow(); queue.remove().run();
        assertEquals("stale", checker.getStatus());
        assertFalse(checker.isUpdateAvailable());
        assertEquals("2", checker.getLatestVersion());
    }

    @Test void dispatchRejectionReleasesRequestOwnership() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        UpdateChecker checker = new UpdateChecker("1", () -> "2", task -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("rejected");
            task.run();
        });
        assertEquals(UpdateChecker.Status.FAILURE, checker.checkNow().join().getStatus());
        assertEquals(UpdateChecker.Status.SUCCESS, checker.checkNow().join().getStatus());
    }

    @Test void numericVersionsHandleLargeComponentsAndMissingZeroes() {
        assertTrue(UpdateChecker.isNewerVersion("1.99999999999999999999", "1.2"));
        assertFalse(UpdateChecker.isNewerVersion("v1.2.0", "1.2"));
        assertFalse(UpdateChecker.isNewerVersion("1.2", "2.0"));
    }
    @Test void releaseBeatsPrereleaseAndBuildMetadataDoesNotChangePrecedence() {
        assertTrue(UpdateChecker.isNewerVersion("1.2", "1.2-rc.1"));
        assertFalse(UpdateChecker.isNewerVersion("1.2-beta", "1.2"));
        assertTrue(UpdateChecker.isNewerVersion("1.2-rc.10", "1.2-rc.2"));
        assertFalse(UpdateChecker.isNewerVersion("1.2+build.5", "1.2+build.1"));
    }
}
