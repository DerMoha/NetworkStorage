package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
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
        if (chest.getHolder() == null || !(chest.getHolder() instanceof org.bukkit.block.BlockState)) {
            return;
        }

        org.bukkit.block.BlockState blockState = (org.bukkit.block.BlockState) chest.getHolder();
        org.bukkit.Location chestLocation = blockState.getLocation();

        Network network = plugin.getNetworkManager().getNetworkByLocation(chestLocation);

        if (network == null) {
            return;
        }

        // Only allow owner or players with permission to auto-insert
        if (!network.getOwner().equals(player.getUniqueId()) &&
                !player.hasPermission("networkstorage.access.all")) {
            return;
        }

        // Don't auto-insert from terminals
        if (network.isTerminalInNetwork(chestLocation) ||
                network.isTerminalInNetwork(network.getNormalizedLocation(chestLocation))) {
            return;
        }

        // This is a network chest, items are automatically part of the network
        // No action needed as items are already accessible through terminals
    }
}
