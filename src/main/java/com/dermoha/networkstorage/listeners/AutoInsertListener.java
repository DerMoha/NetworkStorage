package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * Handles automatic insertion of items into storage networks when chests are closed
 */
public class AutoInsertListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public AutoInsertListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();

        // Only handle chest inventories
        if (event.getInventory().getType() != InventoryType.CHEST) {
            return;
        }

        // Check if this chest is part of a storage network
        Inventory chest = event.getInventory();
        if (chest.getLocation() == null) {
            return;
        }

        StorageNetwork network = plugin.getNetworkManager().getNetworkByLocation(chest.getLocation().getBlock().getLocation());

        // Also check normalized location for double chests
        if (network == null) {
            StorageNetwork tempNetwork = new StorageNetwork("temp", "temp");
            network = plugin.getNetworkManager().getNetworkByLocation(
                    tempNetwork.getNormalizedLocation(chest.getLocation().getBlock().getLocation())
            );
        }

        if (network == null) {
            return;
        }

        // Only allow owner or players with permission to auto-insert
        if (!network.getOwnerUUID().equals(player.getUniqueId().toString()) &&
                !player.hasPermission("networkstorage.access.all")) {
            return;
        }

        // Don't auto-insert from terminals
        if (network.isTerminalInNetwork(chest.getLocation().getBlock().getLocation()) ||
                network.isTerminalInNetwork(network.getNormalizedLocation(chest.getLocation().getBlock().getLocation()))) {
            return;
        }

        // This is a network chest, items are automatically part of the network
        // No action needed as items are already accessible through terminals
    }
}