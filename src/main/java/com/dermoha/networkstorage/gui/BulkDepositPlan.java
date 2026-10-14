package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.util.StorageItems;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.function.IntFunction;

/** A short-lived preview of main inventory slots. It never moves or owns player items. */
public final class BulkDepositPlan {
    private final Map<Integer, ItemStack> items;
    private final long expiresAt;

    private BulkDepositPlan(Map<Integer, ItemStack> items, long now) {
        this.items = Collections.unmodifiableMap(items);
        this.expiresAt = now + 15_000L;
    }

    public static BulkDepositPlan capture(IntFunction<ItemStack> inventory, long now) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = inventory.apply(slot);
            if (!StorageItems.isEmpty(item) && !StorageItems.isTool(item)) {
                items.put(slot, item.clone());
            }
        }
        return new BulkDepositPlan(items, now);
    }

    public boolean matches(IntFunction<ItemStack> inventory, long now) {
        if (now >= expiresAt) return false;
        // Compare every eligible slot so a changed preview requires a fresh confirmation.
        return items.equals(capture(inventory, now).items);
    }

    public Set<Integer> slots() { return items.keySet(); }
    public int itemCount() { return items.values().stream().mapToInt(ItemStack::getAmount).sum(); }
    public boolean isEmpty() { return items.isEmpty(); }
}
