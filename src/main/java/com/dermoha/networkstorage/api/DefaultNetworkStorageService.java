package com.dermoha.networkstorage.api;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

public class DefaultNetworkStorageService implements NetworkStorageService {

    private final NetworkStoragePlugin plugin;

    public DefaultNetworkStorageService(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Collection<Network> getAllNetworks() {
        return plugin.getNetworkManager().getAllNetworks();
    }

    @Override
    public Network getNetwork(String name) {
        return plugin.getNetworkManager().getNetwork(name);
    }

    @Override
    public Network getPlayerNetwork(Player player) {
        return plugin.getNetworkManager().getPlayerNetwork(player);
    }

    @Override
    public List<Network> getAccessibleNetworks(Player player) {
        return plugin.getNetworkManager().getAccessibleNetworks(player);
    }

    @Override
    public List<Network> getOwnedNetworks(Player player) {
        return plugin.getNetworkManager().getOwnedNetworks(player);
    }

    @Override
    public boolean canAccess(Player player, Network network) {
        return network != null && network.canAccess(player);
    }

    @Override
    public ItemStack depositItem(Player player, Network network, ItemStack item) {
        if (network == null || item == null) {
            return item;
        }
        return network.addToNetwork(item);
    }

    @Override
    public ItemStack withdrawItem(Player player, Network network, ItemStack template, int amount) {
        if (network == null || template == null || amount <= 0) {
            return null;
        }
        return network.removeFromNetwork(template, amount);
    }

    @Override
    public int getStoredItemCount(Network network) {
        if (network == null) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, network.getTotalStoredAmount());
    }

    @Override
    public int getTrackedChestCount(Network network) {
        if (network == null) {
            return 0;
        }
        return network.getChestLocations().size() + network.getSenderChestLocations().size();
    }

    @Override
    public String getNetworkOwnerName(Network network) {
        return plugin.getNetworkManager().getNetworkOwnerName(network);
    }

    @Override
    public boolean isRegisteredAt(Location location) {
        return plugin.getNetworkManager().getNetworkByLocation(location) != null;
    }
}
