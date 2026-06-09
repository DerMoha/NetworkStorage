package com.dermoha.networkstorage.util;

import org.bukkit.enchantments.Enchantment;
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

    public static String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        }
        if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    public static boolean matchesEnchantment(Enchantment enchantment, int level, String lowerCaseFilter) {
        String formattedName = formatEnchantmentName(enchantment).toLowerCase();
        String rawName = enchantment.getKey().getKey().toLowerCase();
        String levelStr = String.valueOf(level);
        String romanLevel = toRoman(level).toLowerCase();

        String[] filterParts = lowerCaseFilter.trim().split("\\s+");
        boolean hasNamePart = false;
        boolean hasLevelPart = false;

        for (String part : filterParts) {
            if (formattedName.contains(part) || rawName.contains(part)) {
                hasNamePart = true;
            }
            if (part.equals(levelStr) || part.equals(romanLevel)) {
                hasLevelPart = true;
            }
        }

        if (hasNamePart && hasLevelPart) return true;

        return filterParts.length == 1 && (hasNamePart || hasLevelPart);
    }

    public static String formatEnchantmentName(Enchantment enchantment) {
        String rawName = enchantment.getKey().getKey();
        String[] words = rawName.replace('_', ' ').toLowerCase().split(" ");
        StringBuilder displayName = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                if (!displayName.isEmpty()) {
                    displayName.append(" ");
                }
                displayName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(word.substring(1));
            }
        }
        return displayName.toString();
    }

    // feel free to replace if something better is available
    public static String toRoman(int number) {
        if (number <= 0) return "";
        if (number == 1) return "I";
        if (number == 2) return "II";
        if (number == 3) return "III";
        if (number == 4) return "IV";
        if (number == 5) return "V";
        if (number == 6) return "VI";
        if (number == 7) return "VII";
        if (number == 8) return "VIII";
        if (number == 9) return "IX";
        if (number == 10) return "X";
        return String.valueOf(number);
    }
}
