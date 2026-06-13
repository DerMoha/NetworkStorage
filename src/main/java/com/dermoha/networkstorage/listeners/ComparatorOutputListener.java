package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

public class ComparatorOutputListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public ComparatorOutputListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (!plugin.getConfigManager().isComparatorOutputEnabled()) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        Network network = plugin.getNetworkManager().getNetworkByLocation(block.getLocation());
        if (network == null) {
            return;
        }
        double capacity = network.getCapacityPercent();
        int signalStrength = (int) Math.ceil(capacity / 100.0 * 15.0);
        signalStrength = Math.max(0, Math.min(15, signalStrength));
        event.setNewCurrent(signalStrength);
    }
}
