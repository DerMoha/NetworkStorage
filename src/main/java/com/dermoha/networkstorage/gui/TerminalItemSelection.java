package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects terminal contents without reading or mutating a Bukkit inventory. */
final class TerminalItemSelection {
    static final int PAGE_SIZE = 45;

    private TerminalItemSelection() { }

    static Page select(Map<ItemStack, Integer> items, String filter, SortType sort, int requestedPage) {
        String lowerCaseFilter = filter.toLowerCase(Locale.ROOT);
        List<Map.Entry<ItemStack, Integer>> selected = new ArrayList<>();
        for (Map.Entry<ItemStack, Integer> entry : items.entrySet()) {
            if (filter.isEmpty() || matches(entry.getKey(), lowerCaseFilter)) {
                selected.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        switch (sort) {
            case ALPHABETICAL -> selected.sort(Comparator.comparing(
                    entry -> ItemUtils.getSortableName(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
            case COUNT_DESC -> selected.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
            case COUNT_ASC -> selected.sort(Map.Entry.comparingByValue());
        }
        int totalPages = Math.max(1, (selected.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        int start = page * PAGE_SIZE;
        return new Page(List.copyOf(selected.subList(start, Math.min(start + PAGE_SIZE, selected.size()))),
                page, totalPages);
    }

    private static boolean matches(ItemStack item, String lowerCaseFilter) {
        // Check custom display name
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            if (item.getItemMeta().getDisplayName().toLowerCase(Locale.ROOT).contains(lowerCaseFilter)) {
                return true;
            }
        }

        // Check internal material name (e.g., "diamond_sword")
        if (item.getType().getKey().getKey().toLowerCase(Locale.ROOT).contains(lowerCaseFilter)) {
            return true;
        }

        // Check enchantments of enchanted books
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            // Normal Item Enchants
            if (meta.hasEnchants()) {
                for (Map.Entry<Enchantment, Integer> enchEntry : meta.getEnchants().entrySet()) {
                    if (ItemUtils.matchesEnchantment(enchEntry.getKey(), enchEntry.getValue(), lowerCaseFilter)) {
                        return true;
                    }
                }
            }

            // Enchanted Books
            if (meta instanceof EnchantmentStorageMeta storageMeta) {
                if (storageMeta.hasStoredEnchants()) {
                    for (Map.Entry<Enchantment, Integer> enchEntry : storageMeta.getStoredEnchants().entrySet()) {
                        if (ItemUtils.matchesEnchantment(enchEntry.getKey(), enchEntry.getValue(), lowerCaseFilter)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Fallback check for formatted English name
        return ItemUtils.getItemDisplayName(item).toLowerCase(Locale.ROOT).contains(lowerCaseFilter);
    }

    record Page(List<Map.Entry<ItemStack, Integer>> items, int index, int totalPages) { }
}
