package com.dermoha.networkstorage.runtime;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayDeque;
import java.util.Queue;

/** Spreads a sender pass across ticks instead of loading every sender in one tick. */
public final class SenderChestTransfers {
    private static final int SLOTS_PER_TICK = 8;
    private static final int CONTAINERS_PER_TICK = 2;
    private static final long NANOS_PER_TICK = 2_000_000L;
    private final NetworkStoragePlugin plugin;
    private final long intervalTicks;
    private final Queue<Sender> remaining = new ArrayDeque<>();
    private long tick;
    private long nextPass;
    private Sender active;
    private int slot;

    public SenderChestTransfers(NetworkStoragePlugin plugin, long intervalTicks) {
        this.plugin = plugin;
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    public void tick() {
        long now = tick++;
        if (active == null && remaining.isEmpty()) {
            if (now < nextPass) return;
            nextPass = now + intervalTicks;
            for (Network network : plugin.getNetworkManager().getAllNetworks()) {
                for (Location location : network.getSenderChestLocations()) {
                    remaining.add(new Sender(network, location));
                }
            }
        }
        long deadline = System.nanoTime() + NANOS_PER_TICK;
        int slots = 0;
        int containers = 0;
        while (slots < SLOTS_PER_TICK && containers < CONTAINERS_PER_TICK && System.nanoTime() < deadline) {
            if (active == null) {
                active = remaining.poll();
                slot = 0;
                if (active == null) return;
            }
            containers++;
            Network network = active.network();
            Location location = active.location();
            if (plugin.getNetworkManager().getNetwork(network.getName()) != network
                    || !network.isSenderChestInNetwork(location)) {
                active = null;
                continue;
            }
            if (!plugin.getNetworkManager().ensureLocationChunkLoaded(location)) {
                plugin.getLogger().warning("Sender chest chunk unavailable for " + network.getName() + ": " + location);
                active = null;
                continue;
            }
            if (!(location.getBlock().getState() instanceof InventoryHolder holder)) {
                plugin.getNetworkManager().removeTrackedLocation(network, location);
                active = null;
                continue;
            }
            Inventory inventory = holder.getInventory();
            while (slot < inventory.getSize() && slots < SLOTS_PER_TICK && System.nanoTime() < deadline) {
                network.getMovement().absorbFromInventory(inventory, slot++);
                slots++;
            }
            if (slot >= inventory.getSize()) active = null;
        }
    }

    private record Sender(Network network, Location location) { }
}
