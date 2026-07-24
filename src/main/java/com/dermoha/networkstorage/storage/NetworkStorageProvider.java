package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NetworkStorageProvider {

    void initialize();

    void shutdown();

    void loadNetworks(Map<String, Network> networks,
                      Map<Location, Network> locationIndex,
                      Map<UUID, String> selectedNetworks,
                      Map<UUID, String> selectedWirelessNetworks,
                      OfflinePlayer offlinePlayerLookup);

    void saveNetworks(Collection<Network> networks);

    void savePlayerState(Map<UUID, String> selectedNetworks, Map<UUID, String> selectedWirelessNetworks);

    boolean isAvailable();
}
