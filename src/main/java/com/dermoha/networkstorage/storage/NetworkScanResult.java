package com.dermoha.networkstorage.storage;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable value object describing a storage scan.  Item stacks are copied at
 * the boundary so callers cannot mutate the snapshot held by a Network.
 */
public final class NetworkScanResult {

    private final String networkName;
    private final NetworkScanStatus status;
    private final int registeredLocations;
    private final int uniqueChunks;
    private final int loadedChunks;
    private final int containersFound;
    private final long totalItems;
    private final int uniqueTypes;
    private final long totalSlots;
    private final long usedSlots;
    private final double capacityPercent;
    private final Map<ItemStack, Integer> items;
    private final boolean authoritative;
    private final List<String> warnings;
    private final long scannedAtMs;

    public NetworkScanResult(String networkName,
                             NetworkScanStatus status,
                             int registeredLocations,
                             int uniqueChunks,
                             int loadedChunks,
                             int containersFound,
                             long totalItems,
                             int uniqueTypes,
                             long totalSlots,
                             long usedSlots,
                             double capacityPercent,
                             Map<ItemStack, Integer> items,
                             boolean authoritative,
                             List<String> warnings,
                             long scannedAtMs) {
        this.networkName = networkName;
        this.status = status;
        this.registeredLocations = registeredLocations;
        this.uniqueChunks = uniqueChunks;
        this.loadedChunks = loadedChunks;
        this.containersFound = containersFound;
        this.totalItems = totalItems;
        this.uniqueTypes = uniqueTypes;
        this.totalSlots = totalSlots;
        this.usedSlots = usedSlots;
        this.capacityPercent = capacityPercent;
        this.items = copyItems(items);
        this.authoritative = authoritative;
        this.warnings = warnings == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(warnings));
        this.scannedAtMs = scannedAtMs;
    }

    public static NetworkScanResult pending(String networkName,
                                            int registeredLocations,
                                            int uniqueChunks,
                                            NetworkScanResult previousComplete) {
        return new NetworkScanResult(
                networkName,
                NetworkScanStatus.PENDING,
                registeredLocations,
                uniqueChunks,
                0,
                0,
                previousComplete == null ? 0L : previousComplete.totalItems,
                previousComplete == null ? 0 : previousComplete.uniqueTypes,
                previousComplete == null ? 0L : previousComplete.totalSlots,
                previousComplete == null ? 0L : previousComplete.usedSlots,
                previousComplete == null ? 0.0 : previousComplete.capacityPercent,
                previousComplete == null ? Map.of() : previousComplete.items,
                false,
                List.of(),
                previousComplete == null ? 0L : previousComplete.scannedAtMs);
    }

    public static NetworkScanResult complete(String networkName,
                                             int registeredLocations,
                                             int uniqueChunks,
                                             int loadedChunks,
                                             int containersFound,
                                             long totalItems,
                                             long totalSlots,
                                             long usedSlots,
                                             Map<ItemStack, Integer> items,
                                             long scannedAtMs) {
        double capacity = totalSlots > 0 ? (double) usedSlots * 100.0 / totalSlots : 0.0;
        return new NetworkScanResult(
                networkName,
                NetworkScanStatus.COMPLETE,
                registeredLocations,
                uniqueChunks,
                loadedChunks,
                containersFound,
                totalItems,
                items == null ? 0 : items.size(),
                totalSlots,
                usedSlots,
                capacity,
                items,
                true,
                List.of(),
                scannedAtMs);
    }

    public static NetworkScanResult incomplete(String networkName,
                                               int registeredLocations,
                                               int uniqueChunks,
                                               int loadedChunks,
                                               int containersFound,
                                               long partialTotalItems,
                                               long totalSlots,
                                               long usedSlots,
                                               Map<ItemStack, Integer> partialItems,
                                               List<String> warnings,
                                               NetworkScanResult previousComplete) {
        NetworkScanResult fallback = previousComplete;
        return new NetworkScanResult(
                networkName,
                NetworkScanStatus.INCOMPLETE,
                registeredLocations,
                uniqueChunks,
                loadedChunks,
                containersFound,
                fallback == null ? 0L : fallback.totalItems,
                fallback == null ? 0 : fallback.uniqueTypes,
                fallback == null ? 0L : fallback.totalSlots,
                fallback == null ? 0L : fallback.usedSlots,
                fallback == null ? 0.0 : fallback.capacityPercent,
                fallback == null ? Map.of() : fallback.items,
                false,
                warnings,
                fallback == null ? 0L : fallback.scannedAtMs);
    }

    public String networkName() {
        return networkName;
    }

    public NetworkScanStatus status() {
        return status;
    }

    public int registeredLocations() {
        return registeredLocations;
    }

    public int uniqueChunks() {
        return uniqueChunks;
    }

    public int loadedChunks() {
        return loadedChunks;
    }

    public int containersFound() {
        return containersFound;
    }

    public long totalItems() {
        return totalItems;
    }

    public int uniqueTypes() {
        return uniqueTypes;
    }

    public long totalSlots() {
        return totalSlots;
    }

    public long usedSlots() {
        return usedSlots;
    }

    public double capacityPercent() {
        return capacityPercent;
    }

    public boolean hasAuthoritativeData() {
        return authoritative;
    }

    public List<String> warnings() {
        return warnings;
    }

    public long scannedAtMs() {
        return scannedAtMs;
    }

    public Map<ItemStack, Integer> items() {
        return copyItems(items);
    }

    private static Map<ItemStack, Integer> copyItems(Map<ItemStack, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<ItemStack, Integer> copy = new HashMap<>();
        for (Map.Entry<ItemStack, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey().clone(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
