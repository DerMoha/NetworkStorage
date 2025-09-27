package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Network {

    private String name;
    private final UUID owner;
    private final Set<Location> chestLocations;
    private final Set<Location> terminalLocations;
    private final Map<UUID, PlayerStat> playerStats;
    private final Set<UUID> trustedPlayers;

    public Network(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.chestLocations = new HashSet<>();
        this.terminalLocations = new HashSet<>();
        this.playerStats = new ConcurrentHashMap<>();
        this.trustedPlayers = new HashSet<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Map<UUID, PlayerStat> getPlayerStats() {
        return playerStats;
    }

    public Set<UUID> getTrustedPlayers() {
        return trustedPlayers;
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

    public Map<ItemStack, Integer> getNetworkItems() {
        Map<ItemStack, Integer> networkItems = new HashMap<>();
        for (Location chestLoc : chestLocations) {
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                for (ItemStack item : chest.getInventory().getContents()) {
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
        ItemStack keyItem = item.clone();
        keyItem.setAmount(1);
        map.put(keyItem, item.getAmount());
    }

    public ItemStack removeFromNetwork(ItemStack itemToRemove, int amount) {
        int remaining = amount;
        for (Location chestLoc : chestLocations) {
            if (remaining <= 0) break;
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();
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
        ItemStack result = itemToRemove.clone();
        result.setAmount(amount - remaining);
        return result;
    }

    public ItemStack addToNetwork(ItemStack itemToAdd) {
        if (itemToAdd == null || itemToAdd.getType() == Material.AIR) return null;
        ItemStack remaining = itemToAdd.clone();
        for (Location chestLoc : chestLocations) {
            if (remaining.getAmount() <= 0) break;
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                HashMap<Integer, ItemStack> result = chest.getInventory().addItem(remaining);
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

    public Location getNormalizedLocation(Location location) {
        return location;
    }

    public double getCapacityPercent() {
        int totalSlots = 0;
        int usedSlots = 0;
        for (Location chestLoc : getChestLocations()) {
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
        return totalSlots > 0 ? (double) usedSlots * 100.0 / totalSlots : 0.0;
    }
}
