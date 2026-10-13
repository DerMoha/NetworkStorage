package com.dermoha.networkstorage.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalItemSelectionTest {
    @Test
    void countSortingPreservesOrderForTiesAndDoesNotMutateInput() {
        ItemStack first = item(Material.STONE, null);
        ItemStack second = item(Material.DIAMOND, null);
        ItemStack third = item(Material.IRON_INGOT, null);
        Map<ItemStack, Integer> items = new LinkedHashMap<>();
        items.put(first, 5);
        items.put(second, 10);
        items.put(third, 5);
        assertEquals(List.of(second, first, third), keys(TerminalItemSelection.select(items, "", SortType.COUNT_DESC, 0)));
        assertEquals(List.of(first, third, second), keys(TerminalItemSelection.select(items, "", SortType.COUNT_ASC, 0)));
        assertEquals(List.of(first, second, third), List.copyOf(items.keySet()));
    }

    @Test
    void alphabeticalSortUsesDisplayNames() {
        ItemStack named = item(Material.STONE, "A custom name");
        ItemStack diamond = item(Material.DIAMOND, null);
        assertEquals(List.of(named, diamond), keys(TerminalItemSelection.select(
                Map.of(diamond, 1, named, 1), "", SortType.ALPHABETICAL, 0)));
    }

    @Test
    void matchesCustomNamesMaterialKeysAndFormattedMaterialNames() {
        ItemStack named = item(Material.STONE, "My Special Block");
        ItemStack iron = item(Material.IRON_INGOT, null);
        Map<ItemStack, Integer> items = Map.of(named, 3, iron, 4);
        assertEquals(List.of(named), keys(TerminalItemSelection.select(items, "SPECIAL", SortType.COUNT_ASC, 0)));
        assertEquals(List.of(named), keys(TerminalItemSelection.select(items, "stone", SortType.COUNT_ASC, 0)));
        assertEquals(List.of(iron), keys(TerminalItemSelection.select(items, "iron_ingot", SortType.COUNT_ASC, 0)));
        assertEquals(List.of(iron), keys(TerminalItemSelection.select(items, "iron ingot", SortType.COUNT_ASC, 0)));
    }

    @Test
    void matchingCustomNamesDoesNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            ItemStack named = item(Material.STONE, "IRON");
            assertEquals(List.of(named), keys(TerminalItemSelection.select(
                    Map.of(named, 1), "iron", SortType.COUNT_ASC, 0)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void clampsPagesAndKeepsSlotOrderAfterSorting() {
        Map<ItemStack, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < 46; i++) items.put(item(Material.STONE, "Item " + i), i + 1);
        TerminalItemSelection.Page first = TerminalItemSelection.select(items, "", SortType.COUNT_DESC, -3);
        assertEquals(0, first.index());
        assertEquals(2, first.totalPages());
        assertEquals(45, first.items().size());
        assertEquals(46, first.items().getFirst().getValue());
        TerminalItemSelection.Page last = TerminalItemSelection.select(items, "", SortType.COUNT_DESC, 100);
        assertEquals(1, last.index());
        assertEquals(1, last.items().size());
        assertEquals(1, last.items().getFirst().getValue());
        TerminalItemSelection.Page filtered = TerminalItemSelection.select(items, "Item 45", SortType.COUNT_DESC, 1);
        assertEquals(0, filtered.index());
        assertEquals(1, filtered.totalPages());
        assertEquals(46, filtered.items().getFirst().getValue());
    }

    @Test
    void emptyResultsHaveOneEmptyPage() {
        TerminalItemSelection.Page empty = TerminalItemSelection.select(
                Map.of(item(Material.STONE, null), 1), "unmatched", SortType.COUNT_ASC, 50);
        assertEquals(0, empty.index());
        assertEquals(1, empty.totalPages());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void selectedCountsAreDetachedFromSourceMap() {
        ItemStack stone = item(Material.STONE, null);
        Map<ItemStack, Integer> items = new LinkedHashMap<>();
        items.put(stone, 4);
        TerminalItemSelection.Page page = TerminalItemSelection.select(items, "", SortType.COUNT_ASC, 0);
        items.put(stone, 8);
        assertEquals(4, page.items().getFirst().getValue());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    private List<ItemStack> keys(TerminalItemSelection.Page page) {
        return page.items().stream().map(Map.Entry::getKey).toList();
    }

    private ItemStack item(Material material, String name) {
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasDisplayName" -> name != null;
                    case "getDisplayName" -> name;
                    case "hasEnchants" -> false;
                    case "getEnchants" -> Map.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ItemStack() {
            @Override public Material getType() { return material; }
            @Override public boolean hasItemMeta() { return name != null; }
            @Override public ItemMeta getItemMeta() { return meta; }
            @Override public boolean equals(Object other) { return this == other; }
            @Override public int hashCode() { return System.identityHashCode(this); }
        };
    }
}
