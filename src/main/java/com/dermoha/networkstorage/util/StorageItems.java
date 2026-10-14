package com.dermoha.networkstorage.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Tools needed to access storage must never be accepted as player deposits. */
public final class StorageItems {
    private static final NamespacedKey WAND = new NamespacedKey("networkstorage", "storage_wand");
    private static final NamespacedKey WIRELESS = new NamespacedKey("networkstorage", "wireless_terminal");
    private StorageItems() {}

    public static boolean isEmpty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return true;
        var type = item.getType();
        return type == org.bukkit.Material.AIR || type == org.bukkit.Material.CAVE_AIR || type == org.bukkit.Material.VOID_AIR;
    }

    public static boolean isTool(ItemStack item) {
        if (isEmpty(item) || !item.hasItemMeta()) return false;
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.has(WAND, PersistentDataType.BYTE) || data.has(WIRELESS, PersistentDataType.BYTE);
    }
}
