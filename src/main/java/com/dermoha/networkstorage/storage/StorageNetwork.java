package com.dermoha.networkstorage.storage;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Chest;
import org.bukkit.inventory.InventoryHolder;

import java.util.*;

public class StorageNetwork {

    private final String networkId;
    private final Set<Location> chestLocations;
    private final Set<Location> terminalLocations;
    private final String ownerUUID;

    public StorageNetwork(String networkId, String ownerUUID) {
        this.networkId = networkId;
        this.ownerUUID = ownerUUID;
        this.chestLocations = new HashSet<>();
        this.terminalLocations = new HashSet<>();
    }

    public String getNetworkId() {
        return networkId;
    }

    public String getOwnerUUID() {
        return ownerUUID;
    }

    public Set<Location> getChestLocations() {
        return new HashSet<>(chestLocations);
    }

    public Set<Location> getTerminalLocations() {
        return new HashSet<>(terminalLocations);
    }

    public void addChest(Location location) {
        chestLocations.add(location);
    }

    public void removeChest(Location location) {
        chestLocations.remove(location);
    }

    public void addTerminal(Location location) {
        terminalLocations.add(location);
    }

    public void removeTerminal(Location location) {
        terminalLocations.remove(location);
    }

    public boolean isChestInNetwork(Location location) {
        return chestLocations.contains(location);
    }

    public boolean isTerminalInNetwork(Location location) {
        return terminalLocations.contains(location);
    }

    /**
     * Gets all items in the network with their quantities
     * @return Map of ItemStack to total quantity
     */
    public Map<ItemStack, Integer> getNetworkItems() {
        Map<ItemStack, Integer> networkItems = new HashMap<>();
        Set<Location> processedLocations = new HashSet<>();

        for (Location chestLoc : chestLocations) {
            // Skip if we've already processed this location (for double chests)
            if (processedLocations.contains(chestLoc)) {
                continue;
            }

            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                // Handle double chests - check by inventory size (54 = double chest)
                if (inv.getSize() == 54) {
                    // For double chests, we need to be careful not to process the same inventory twice
                    // We'll use a simpler approach: just mark this location as processed
                } else {
                    processedLocations.add(chestLoc);
                }
                processedLocations.add(chestLoc);

                // Count items in the inventory (this gets the full double chest inventory if applicable)
                for (ItemStack item : inv.getContents()) {
                    if (item != null && item.getType() != Material.AIR) {
                        addToNetworkMap(networkItems, item);
                    }
                }
            }
        }

        return networkItems;
    }

    private void addToNetworkMap(Map<ItemStack, Integer> map, ItemStack item) {
        for (Map.Entry<ItemStack, Integer> entry : map.entrySet()) {
            if (entry.getKey().isSimilar(item)) {
                map.put(entry.getKey(), entry.getValue() + item.getAmount());
                return;
            }
        }

        // If no similar item found, add new entry
        ItemStack keyItem = item.clone();
        keyItem.setAmount(1);
        map.put(keyItem, item.getAmount());
    }

    /**
     * Attempts to remove a stack of items from the network
     * @param itemToRemove The item type to remove
     * @param amount The amount to remove
     * @return The actual ItemStack removed, or null if not enough items
     */
    public ItemStack removeFromNetwork(ItemStack itemToRemove, int amount) {
        int remaining = amount;
        List<Location> chestsToCheck = new ArrayList<>(chestLocations);
        Set<Location> processedLocations = new HashSet<>();

        // First pass: check if we have enough items
        int totalAvailable = 0;
        for (Location chestLoc : chestsToCheck) {
            if (processedLocations.contains(chestLoc)) {
                continue;
            }

            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                // Handle double chests - check by inventory size
                if (inv.getSize() == 54) {
                    // For double chests, we mark this location as processed
                } else {
                    processedLocations.add(chestLoc);
                }
                processedLocations.add(chestLoc);

                for (ItemStack item : inv.getContents()) {
                    if (item != null && item.isSimilar(itemToRemove)) {
                        totalAvailable += item.getAmount();
                    }
                }
            }
        }

        if (totalAvailable < amount) {
            return null; // Not enough items
        }

        // Reset processed locations for second pass
        processedLocations.clear();

        // Second pass: actually remove the items
        for (Location chestLoc : chestsToCheck) {
            if (remaining <= 0) break;
            if (processedLocations.contains(chestLoc)) {
                continue;
            }

            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                // Handle double chests - check by inventory size
                if (inv.getSize() == 54) {
                    // For double chests, we mark this location as processed
                } else {
                    processedLocations.add(chestLoc);
                }
                processedLocations.add(chestLoc);

                for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.isSimilar(itemToRemove)) {
                        int toRemove = Math.min(remaining, item.getAmount());

                        if (item.getAmount() <= toRemove) {
                            inv.setItem(i, null);
                        } else {
                            item.setAmount(item.getAmount() - toRemove);
                        }

                        remaining -= toRemove;
                    }
                }
            }
        }

        // Return the removed item
        ItemStack result = itemToRemove.clone();
        result.setAmount(amount - remaining);
        return result;
    }

    /**
     * Attempts to add items to the network
     * @param itemToAdd The item to add
     * @return The remaining items that couldn't be stored (null if all stored)
     */
    public ItemStack addToNetwork(ItemStack itemToAdd) {
        if (itemToAdd == null || itemToAdd.getType() == Material.AIR) {
            return null;
        }

        ItemStack remaining = itemToAdd.clone();
        Set<Location> processedLocations = new HashSet<>();

        for (Location chestLoc : chestLocations) {
            if (remaining.getAmount() <= 0) break;
            if (processedLocations.contains(chestLoc)) {
                continue;
            }

            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                // Handle double chests - check by inventory size
                if (inv.getSize() == 54) {
                    // For double chests, we mark this location as processed
                } else {
                    processedLocations.add(chestLoc);
                }
                processedLocations.add(chestLoc);

                HashMap<Integer, ItemStack> result = inv.addItem(remaining);

                if (result.isEmpty()) {
                    remaining = null;
                    break;
                } else {
                    remaining = result.get(0);
                }
            }
        }

        return remaining;
    }

    /**
     * Gets the normalized location for a chest (handles double chests)
     * @param location The location to normalize
     * @return The normalized location
     */
    public Location getNormalizedLocation(Location location) {
        // For simplicity, we'll just return the same location
        // The double chest handling is now done through inventory size checks
        return location;
    }

    /**
     * Zählt die Gesamtmenge eines bestimmten Items im Netzwerk
     * @param itemToCount Das zu zählende Item
     * @return Gesamtanzahl im Netzwerk
     */
    public int getItemCount(ItemStack itemToCount) {
        if (itemToCount == null || itemToCount.getType() == Material.AIR) {
            return 0;
        }
        int count = 0;
        Set<Location> processedLocations = new HashSet<>();
        for (Location chestLoc : chestLocations) {
            if (processedLocations.contains(chestLoc)) {
                continue;
            }
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();
                // Handle double chests
                if (inv.getSize() == 54) {
                    // For double chests, we mark this location as processed
                } else {
                    processedLocations.add(chestLoc);
                }
                processedLocations.add(chestLoc);
                for (ItemStack stack : inv.getContents()) {
                    if (stack != null && stack.isSimilar(itemToCount)) {
                        count += stack.getAmount();
                    }
                }
            }
        }
        return count;
    }
}