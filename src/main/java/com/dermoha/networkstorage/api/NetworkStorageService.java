package com.dermoha.networkstorage.api;

import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NetworkStorageService {

    Collection<Network> getAllNetworks();

    Network getNetwork(String name);

    Network getPlayerNetwork(Player player);

    List<Network> getAccessibleNetworks(Player player);

    List<Network> getOwnedNetworks(Player player);

    boolean canAccess(Player player, Network network);

    ItemStack depositItem(Player player, Network network, ItemStack item);

    ItemStack withdrawItem(Player player, Network network, ItemStack template, int amount);

    int getStoredItemCount(Network network);

    int getTrackedChestCount(Network network);

    String getNetworkOwnerName(Network network);

    boolean isRegisteredAt(Location location);
}
