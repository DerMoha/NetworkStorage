package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkMovement;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class HopperIntegrationListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public HopperIntegrationListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!plugin.getConfigManager().isHopperIntegrationEnabled()) {
            return;
        }
        Inventory destination = event.getDestination();
        Inventory source = event.getSource();

        if (isHopper(source) || isHopper(destination)) {
            Network sourceNetwork = findNetworkAtInventoryHolder(source.getHolder());
            Network destinationNetwork = findNetworkAtInventoryHolder(destination.getHolder());

            if (sourceNetwork != null && isHopper(destination)) {
                ItemStack item = event.getItem().clone();
                ItemStack remaining = sourceNetwork.addToNetwork(item);
                if (remaining == null || remaining.getAmount() == 0) {
                    event.setCancelled(true);
                    event.getDestination().setItem(event.getDestination().first(event.getItem().getType()), null);
                } else {
                    event.setItem(remaining);
                }
                return;
            }

            if (destinationNetwork != null && isHopper(source)) {
                ItemStack template = event.getItem().clone();
                template.setAmount(1);
                ItemStack removed = destinationNetwork.removeFromNetwork(template, event.getItem().getAmount());
                if (removed == null || removed.getAmount() <= 0) {
                    event.setCancelled(true);
                } else {
                    event.setItem(removed);
                }
            }
        }
    }

    private boolean isHopper(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Hopper;
    }

    private Network findNetworkAtInventoryHolder(org.bukkit.inventory.InventoryHolder holder) {
        if (!(holder instanceof org.bukkit.block.Container container)) {
            return null;
        }
        Block block = container.getBlock();
        if (!plugin.getConfigManager().isNetworkContainerBlock(block.getType())) {
            return null;
        }
        return plugin.getNetworkManager().getNetworkByLocation(block.getLocation());
    }
}
