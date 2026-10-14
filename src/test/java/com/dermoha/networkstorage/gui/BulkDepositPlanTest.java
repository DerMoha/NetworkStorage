package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.storage.MovementEvents;
import com.dermoha.networkstorage.storage.NetworkMovement;
import com.dermoha.networkstorage.util.StorageItems;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BulkDepositPlanTest {
    @Test
    void previewExcludesHotbarArmorOffhandAndToolsAnywhereInInventory() {
        Map<Integer, ItemStack> inventory = new HashMap<>();
        for (int slot : new int[] {0, 8, 9, 35, 36, 39, 40}) inventory.put(slot, item(4, null));
        inventory.put(10, item(1, "wireless_terminal"));
        inventory.put(11, item(1, "storage_wand"));
        BulkDepositPlan plan = BulkDepositPlan.capture(inventory::get, 100);
        assertEquals(Set.of(9, 35), plan.slots());
        assertEquals(8, plan.itemCount());
        assertTrue(plan.matches(inventory::get, 101));
        assertFalse(plan.matches(inventory::get, 15_100));
    }

    @Test
    void changedOrNewMainInventoryItemsRequireAnotherPreview() {
        Map<Integer, ItemStack> inventory = new HashMap<>();
        inventory.put(9, item(4, null));
        BulkDepositPlan plan = BulkDepositPlan.capture(inventory::get, 0);
        inventory.get(9).setAmount(3);
        assertFalse(plan.matches(inventory::get, 1));
        inventory.get(9).setAmount(4);
        inventory.put(10, item(1, null));
        assertFalse(plan.matches(inventory::get, 1));
        inventory.remove(10);
        inventory.put(0, item(64, null));
        assertTrue(plan.matches(inventory::get, 1));
        assertEquals(4, plan.itemCount(), "preview must hold a detached copy");
    }

    @Test
    void protectedToolsCannotBeDepositedEvenByDirectOrShiftClickPaths() {
        for (String key : List.of("wireless_terminal", "storage_wand")) {
            ItemStack tool = item(1, key);
            assertTrue(StorageItems.isTool(tool));
            NetworkMovement.ItemSource source = new NetworkMovement.ItemSource() {
                public ItemStack read() { return tool; }
                public void write(ItemStack item) { fail("protected tool must remain untouched"); }
            };
            // No network is needed: rejection must happen before any network mutation.
            var result = new NetworkMovement(null, MovementEvents.NOOP).depositFromPlayer(null, source);
            assertEquals(0, result.deposited());
            assertEquals(1, result.returned());
        }
        assertFalse(StorageItems.isTool(item(1, null)));
        assertTrue(BulkDepositPlan.capture(slot -> null, 0).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2, 5})
    void depositsPreserveLeftoversWhenNetworkHasLimitedSpace(int remaining) {
        ItemStack[] slot = {item(5, null)};
        int[] recorded = {0};
        var network = new com.dermoha.networkstorage.storage.Network("Main", UUID.randomUUID(), null) {
            @Override public ItemStack addToNetwork(ItemStack offered) {
                assertNotSame(slot[0], offered, "deposit must offer a detached item");
                return remaining == 0 ? null : item(remaining, null);
            }
            @Override public void recordItemsDeposited(org.bukkit.entity.Player player, int amount) {
                recorded[0] += amount;
            }
        };
        var source = new NetworkMovement.ItemSource() {
            public ItemStack read() { return slot[0]; }
            public void write(ItemStack value) { slot[0] = value; }
        };
        var result = new NetworkMovement(network, MovementEvents.NOOP).depositFromPlayer(null, source);
        assertEquals(5 - remaining, result.deposited());
        assertEquals(remaining, result.returned());
        assertEquals(5 - remaining, recorded[0]);
        if (remaining == 0) assertNull(slot[0]);
        else assertEquals(remaining, slot[0].getAmount());
    }

    private ItemStack item(int amount, String tool) { return new TestItem(amount, tool); }

    private static class TestItem extends ItemStack {
        private int amount;
        private final String tool;
        TestItem(int amount, String tool) { this.amount = amount; this.tool = tool; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean hasItemMeta() { return tool != null; }
        @Override public ItemStack clone() { return new TestItem(amount, tool); }
        @Override public boolean equals(Object other) { return other instanceof TestItem item && amount == item.amount && Objects.equals(tool, item.tool); }
        @Override public int hashCode() { return Objects.hash(amount, tool); }
        @Override public ItemMeta getItemMeta() {
            var data = (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                    new Class<?>[] {PersistentDataContainer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("has")) return ((NamespacedKey) args[0]).getKey().equals(tool);
                        throw new UnsupportedOperationException(method.getName());
                    });
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[] {ItemMeta.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPersistentDataContainer")) return data;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
