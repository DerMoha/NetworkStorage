package com.dermoha.networkstorage.storage;

import org.bukkit.command.CommandSender;
import com.dermoha.networkstorage.TestItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkScanResultTest {

    @Test
    void pendingAndIncompleteScansPreserveLastCompleteSnapshot() {
        Network network = new Network("Global", UUID.randomUUID(), new FakeAccessRules());
        ItemStack diamonds = new TestItemStack(Material.DIAMOND, 1);
        NetworkScanResult complete = NetworkScanResult.complete(
                "Global", 1, 1, 1, 1, 47_312L, 27, 1,
                Map.of(diamonds, 47_312), System.currentTimeMillis());

        network.applyScanResult(complete);
        assertTrue(network.hasCompleteScan());
        assertEquals(47_312L, network.getTotalStoredAmount());

        long version = network.getContentVersion();
        network.invalidateItemCache();
        assertEquals(version + 1, network.getContentVersion());
        assertEquals(NetworkScanStatus.PENDING, network.getScanResult().status());
        assertEquals(47_312L, network.getScanResult().totalItems());
        assertEquals(47_312, network.getNetworkItems().values().iterator().next());

        NetworkScanResult incomplete = new NetworkScanResult(
                "Global", NetworkScanStatus.INCOMPLETE, 1, 1, 0, 0,
                0L, 0, 27, 0, 0.0, Map.of(), false,
                java.util.List.of("container unavailable"), System.currentTimeMillis());
        network.applyScanResult(incomplete);

        assertEquals(NetworkScanStatus.INCOMPLETE, network.getScanResult().status());
        assertFalse(network.getScanResult().hasAuthoritativeData());
        assertEquals(47_312L, network.getScanResult().totalItems());
        assertEquals(47_312L, network.getLastCompleteStoredAmount());
        assertEquals(47_312, network.getNetworkItems().values().iterator().next());
    }

    @Test
    void emptyCompleteScanIsAuthoritativeZeroInsteadOfUnknownZero() {
        Network network = new Network("Empty", UUID.randomUUID(), new FakeAccessRules());
        network.applyScanResult(NetworkScanResult.complete(
                "Empty", 0, 0, 0, 0, 0L, 0L, 0L, Map.of(), System.currentTimeMillis()));

        assertTrue(network.hasCompleteScan());
        assertTrue(network.getScanResult().hasAuthoritativeData());
        assertEquals(0L, network.getScanResult().totalItems());
    }

    private static final class FakeAccessRules implements NetworkAccessRules {
        @Override
        public boolean isGlobalNetworkMode() {
            return true;
        }

        @Override
        public boolean hasPrivilege(CommandSender sender, String permission) {
            return false;
        }

        @Override
        public boolean isTrustSystemEnabled() {
            return false;
        }
    }
}
