package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Network {

    private String name;
    private final UUID owner;
    private final Set<Location> chestLocations;
    private final Set<Location> terminalLocations;
    private final Set<Location> senderChestLocations;
    private final Map<UUID, PlayerStat> playerStats;
    private final Set<UUID> trustedPlayers;
    private Map<ItemStack, Integer> cachedNetworkItems;
    private Material iconMaterial; // Visual icon for the network

    // Thread safety: ReadWriteLock allows multiple concurrent reads but exclusive writes
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private NetworkManager networkManager;

    public Network(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.chestLocations = new HashSet<>();
        this.terminalLocations = new HashSet<>();
        this.senderChestLocations = new HashSet<>();
        this.playerStats = new ConcurrentHashMap<>();
        this.trustedPlayers = new HashSet<>();
        this.cachedNetworkItems = new ConcurrentHashMap<>(); // Use ConcurrentHashMap for thread safety
        this.iconMaterial = Material.CHEST; // Default icon
    }

    /**
     * Set the NetworkManager reference for marking dirty state
     */
    public void setNetworkManager(NetworkManager networkManager) {
        this.networkManager = networkManager;
    }

    /**
     * Mark the network as dirty (needs saving) if NetworkManager is available
     */
    private void markDirty() {
        if (networkManager != null) {
            networkManager.markDirty();
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        markDirty();
    }

    public Material getIconMaterial() {
        return iconMaterial != null ? iconMaterial : Material.CHEST;
    }

    public void setIconMaterial(Material iconMaterial) {
        this.iconMaterial = iconMaterial;
        markDirty();
    }

    public UUID getOwner() {
        return owner;
    }

    public Set<Location> getChestLocations() {
        return new HashSet<>(chestLocations);
    }

    public Set<Location> getTerminalLocations() {
        return new HashSet<>(terminalLocations);
    }

    public Set<Location> getSenderChestLocations() {
        return new HashSet<>(senderChestLocations);
    }

    public Map<UUID, PlayerStat> getPlayerStats() {
        return playerStats;
    }

    public Set<UUID> getTrustedPlayers() {
        return trustedPlayers;
    }

    public void addChest(Location location) {
        chestLocations.add(location);
        rebuildCache();
    }

    public void removeChest(Location location) {
        chestLocations.remove(location);
        rebuildCache();
    }

    public void addTerminal(Location location) {
        terminalLocations.add(location);
        rebuildCache();
    }

    public void removeTerminal(Location location) {
        terminalLocations.remove(location);
        rebuildCache();
    }

    public void addSenderChest(Location location) {
        senderChestLocations.add(location);
        rebuildCache();
    }

    public void removeSenderChest(Location location) {
        senderChestLocations.remove(location);
        rebuildCache();
    }

    public boolean isChestInNetwork(Location location) {
        return chestLocations.contains(location);
    }

    public boolean isTerminalInNetwork(Location location) {
        return terminalLocations.contains(location);
    }

    public boolean isSenderChestInNetwork(Location location) {
        return senderChestLocations.contains(location);
    }

    public PlayerStat getPlayerStat(Player player) {
        return playerStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStat(player.getUniqueId(), player.getName()));
    }

    public void recordItemsDeposited(Player player, int amount) {
        getPlayerStat(player).addItemsDeposited(amount);
    }

    public void recordItemsWithdrawn(Player player, int amount) {
        getPlayerStat(player).addItemsWithdrawn(amount);
    }

    public boolean canAccess(Player player) {
        ConfigManager configManager = NetworkStoragePlugin.getInstance().getConfigManager();
        if (configManager.getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return true;
        }
        if (!configManager.isTrustSystemEnabled()) {
            return true;
        }
        UUID playerUUID = player.getUniqueId();
        return playerUUID.equals(this.owner) || trustedPlayers.contains(playerUUID) || player.hasPermission("networkstorage.admin");
    }

    public boolean isTrusted(UUID playerUUID) {
        return trustedPlayers.contains(playerUUID);
    }

    public void addTrustedPlayer(UUID playerUUID) {
        trustedPlayers.add(playerUUID);
    }

    public void removeTrustedPlayer(UUID playerUUID) {
        trustedPlayers.remove(playerUUID);
    }

    /**
     * Get a snapshot of all items in the network
     * Thread-safe: Uses read lock for concurrent access
     */
    public Map<ItemStack, Integer> getNetworkItems() {
        cacheLock.readLock().lock();
        try {
            if (cachedNetworkItems == null || cachedNetworkItems.isEmpty()) {
                // Upgrade to write lock to rebuild cache
                cacheLock.readLock().unlock();
                rebuildCache();
                cacheLock.readLock().lock();
            }
            return new HashMap<>(cachedNetworkItems);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Rebuild the cache from physical chest contents
     * Thread-safe: Uses write lock for exclusive access
     * This method scans all chests and sender chests to rebuild the item cache
     */
    public void rebuildCache() {
        cacheLock.writeLock().lock();
        try {
            // Build new cache in temporary map to avoid exposing empty/partial cache
            Map<ItemStack, Integer> newCache = new ConcurrentHashMap<>();
            Set<Location> allChestLocations = new HashSet<>(chestLocations);
            allChestLocations.addAll(senderChestLocations);

            for (Location chestLoc : allChestLocations) {
                if (chestLoc.getWorld() != null && chestLoc.getWorld().isChunkLoaded(chestLoc.getBlockX() >> 4, chestLoc.getBlockZ() >> 4)) {
                    if (chestLoc.getBlock().getState() instanceof Chest) {
                        Chest chest = (Chest) chestLoc.getBlock().getState();
                        for (ItemStack item : chest.getInventory().getContents()) {
                            if (item != null && item.getType() != Material.AIR) {
                                ItemStack key = createCacheKey(item);
                                newCache.put(key, newCache.getOrDefault(key, 0) + item.getAmount());
                            }
                        }
                    }
                }
            }

            // Atomically replace the cache
            cachedNetworkItems = newCache;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Create a normalized cache key from an ItemStack
     * The key is a clone with amount set to 1, ensuring consistent HashMap behavior
     */
    private ItemStack createCacheKey(ItemStack item) {
        ItemStack key = item.clone();
        key.setAmount(1);
        return key;
    }

    /**
     * Find the cache key that matches the given item
     * This handles ItemStack equality properly by using isSimilar()
     */
    private ItemStack findCacheKey(ItemStack item) {
        ItemStack searchKey = createCacheKey(item);
        for (ItemStack key : cachedNetworkItems.keySet()) {
            if (key.isSimilar(searchKey)) {
                return key;
            }
        }
        return searchKey;
    }

    /**
     * Update cached item count (must be called within write lock)
     */
    private void updateCachedItems(ItemStack item, int amount) {
        ItemStack key = findCacheKey(item);
        int newAmount = cachedNetworkItems.getOrDefault(key, 0) + amount;

        if (newAmount > 0) {
            cachedNetworkItems.put(key, newAmount);
        } else {
            cachedNetworkItems.remove(key);
        }
    }

    /**
     * Get the amount of a specific item in the cache
     */
    private int getAmountInCache(ItemStack item) {
        for (Map.Entry<ItemStack, Integer> entry : cachedNetworkItems.entrySet()) {
            if (entry.getKey().isSimilar(item)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * Remove items from the network storage
     * Thread-safe: Uses write lock for exclusive access
     *
     * @param itemToRemove The type of item to remove
     * @param amount The amount to remove
     * @return ItemStack containing the amount actually removed
     */
    public ItemStack removeFromNetwork(ItemStack itemToRemove, int amount) {
        cacheLock.writeLock().lock();
        try {
            // Check cache first - only remove what we have
            int availableInCache = getAmountInCache(itemToRemove);
            int amountToActuallyRemove = Math.min(amount, availableInCache);

            if (amountToActuallyRemove <= 0) {
                ItemStack result = itemToRemove.clone();
                result.setAmount(0);
                return result;
            }

            // Remove from physical storage (both regular chests and sender chests)
            int amountRemoved = 0;
            Set<Location> allChestLocations = new HashSet<>(chestLocations);
            allChestLocations.addAll(senderChestLocations);

            for (Location chestLoc : allChestLocations) {
                if (amountRemoved >= amountToActuallyRemove) break;

                if (chestLoc.getWorld() != null && chestLoc.getWorld().isChunkLoaded(chestLoc.getBlockX() >> 4, chestLoc.getBlockZ() >> 4)) {
                    if (chestLoc.getBlock().getState() instanceof Chest) {
                        Chest chest = (Chest) chestLoc.getBlock().getState();
                        Inventory inv = chest.getInventory();

                        for (int i = 0; i < inv.getSize() && amountRemoved < amountToActuallyRemove; i++) {
                            ItemStack item = inv.getItem(i);
                            if (item != null && item.isSimilar(itemToRemove)) {
                                int toRemove = Math.min(amountToActuallyRemove - amountRemoved, item.getAmount());

                                if (item.getAmount() <= toRemove) {
                                    inv.setItem(i, null);
                                } else {
                                    item.setAmount(item.getAmount() - toRemove);
                                }

                                amountRemoved += toRemove;
                            }
                        }
                    }
                }
            }

            // Update cache to reflect removal
            if (amountRemoved > 0) {
                updateCachedItems(itemToRemove, -amountRemoved);
                markDirty();
            }

            ItemStack result = itemToRemove.clone();
            result.setAmount(amountRemoved);
            return result;

        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Add items to the network storage
     * Thread-safe: Uses write lock for exclusive access
     * Tries to add to regular chests first, then sender chests if space available
     *
     * @param itemToAdd The ItemStack to add
     * @return ItemStack containing any remaining items that couldn't be added, or null if all added
     */
    public ItemStack addToNetwork(ItemStack itemToAdd) {
        if (itemToAdd == null || itemToAdd.getType() == Material.AIR) return null;

        cacheLock.writeLock().lock();
        try {
            ItemStack remaining = itemToAdd.clone();
            int originalAmount = remaining.getAmount();

            // First, try to add to regular chests
            for (Location chestLoc : chestLocations) {
                if (remaining.getAmount() <= 0) break;

                if (chestLoc.getWorld() != null && chestLoc.getWorld().isChunkLoaded(chestLoc.getBlockX() >> 4, chestLoc.getBlockZ() >> 4)) {
                    if (chestLoc.getBlock().getState() instanceof Chest) {
                        Chest chest = (Chest) chestLoc.getBlock().getState();
                        HashMap<Integer, ItemStack> result = chest.getInventory().addItem(remaining.clone());

                        if (result.isEmpty()) {
                            remaining.setAmount(0);
                            break;
                        } else {
                            remaining = result.get(0);
                        }
                    }
                }
            }

            // If still items remaining, try sender chests
            if (remaining.getAmount() > 0) {
                for (Location senderLoc : senderChestLocations) {
                    if (remaining.getAmount() <= 0) break;

                    if (senderLoc.getWorld() != null && senderLoc.getWorld().isChunkLoaded(senderLoc.getBlockX() >> 4, senderLoc.getBlockZ() >> 4)) {
                        if (senderLoc.getBlock().getState() instanceof Chest) {
                            Chest chest = (Chest) senderLoc.getBlock().getState();
                            HashMap<Integer, ItemStack> result = chest.getInventory().addItem(remaining.clone());

                            if (result.isEmpty()) {
                                remaining.setAmount(0);
                                break;
                            } else {
                                remaining = result.get(0);
                            }
                        }
                    }
                }
            }

            // Update cache with amount actually added
            int amountAdded = originalAmount - remaining.getAmount();
            if (amountAdded > 0) {
                updateCachedItems(itemToAdd, amountAdded);
                markDirty();
            }

            return remaining.getAmount() > 0 ? remaining : null;

        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    public Location getNormalizedLocation(Location location) {
        return location;
    }

    public double getCapacityPercent() {
        int totalSlots = 0;
        int usedSlots = 0;
        Set<Location> allChestLocations = new HashSet<>(getChestLocations());
        allChestLocations.addAll(getSenderChestLocations());
        for (Location chestLoc : allChestLocations) {
            if (chestLoc.getWorld() != null && chestLoc.getWorld().isChunkLoaded(chestLoc.getBlockX() >> 4, chestLoc.getBlockZ() >> 4)) {
                if (chestLoc.getBlock().getState() instanceof Chest) {
                    Chest chest = (Chest) chestLoc.getBlock().getState();
                    Inventory inv = chest.getInventory();
                    totalSlots += inv.getSize();
                    for (ItemStack item : inv.getContents()) {
                        if (item != null && item.getType() != Material.AIR) {
                            usedSlots++;
                        }
                    }
                }
            }
        }
        return totalSlots > 0 ? (double) usedSlots * 100.0 / totalSlots : 0.0;
    }
}
