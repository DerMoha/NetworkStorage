package com.dermoha.networkstorage.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemUtils {

    private ItemUtils() {
    }

    public static String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        String materialName = item.getType().toString().replace('_', ' ').toLowerCase();
        String[] words = materialName.split(" ");
        StringBuilder displayName = new StringBuilder();
        for (String word : words) {
            if (displayName.length() > 0) {
                displayName.append(" ");
            }
            displayName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return displayName.toString();
    }

    public static void applyCustomModelData(ItemMeta meta, Integer customModelData) {
        if (meta != null && customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
    }
}
