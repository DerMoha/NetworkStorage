package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkManager {

    private final NetworkStoragePlugin plugin;
    private final Map<String, StorageNetwork> networks;
    private final Map<String, String> playerNetworks; // Player UUID -> Network ID
    private final File networksFile;

    public NetworkManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.networks = new ConcurrentHashMap<>();
        this.playerNetworks = new ConcurrentHashMap<>();
        this.networksFile = new File(plugin.getDataFolder(), "networks.yml");

        loadNetworks();
    }

    public StorageNetwork createNetwork(Player owner) {
        String networkId = generateNetworkId();
        String playerUUID = owner.getUniqueId().toString();

        StorageNetwork network = new StorageNetwork(networkId, playerUUID);
        networks.put(networkId, network);
        playerNetworks.put(playerUUID, networkId);

        saveNetworks();
        return network;
    }

    public StorageNetwork getPlayerNetwork(Player player) {
        String playerUUID = player.getUniqueId().toString();
        String networkId = playerNetworks.get(playerUUID);

        if (networkId != null) {
            return networks.get(networkId);
        }

        return null;
    }

    public StorageNetwork getOrCreatePlayerNetwork(Player player) {
        StorageNetwork network = getPlayerNetwork(player);
        if (network == null) {
            network = createNetwork(player);
        }
        return network;
    }

    public StorageNetwork getNetworkByLocation(Location location) {
        for (StorageNetwork network : networks.values()) {
            if (network.isChestInNetwork(location) || network.isTerminalInNetwork(location)) {
                return network;
            }
        }
        return null;
    }

    public void deleteNetwork(String networkId) {
        StorageNetwork network = networks.remove(networkId);
        if (network != null) {
            playerNetworks.remove(network.getOwnerUUID());
            saveNetworks();
        }
    }

    public Collection<StorageNetwork> getAllNetworks() {
        return networks.values();
    }

    private String generateNetworkId() {
        return "network_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }

    public void saveNetworks() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            FileConfiguration config = new YamlConfiguration();

            for (StorageNetwork network : networks.values()) {
                String path = "networks." + network.getNetworkId();
                config.set(path + ".owner", network.getOwnerUUID());

                // Save chest locations
                List<String> chestLocs = new ArrayList<>();
                for (Location loc : network.getChestLocations()) {
                    chestLocs.add(locationToString(loc));
                }
                config.set(path + ".chests", chestLocs);

                // Save terminal locations
                List<String> terminalLocs = new ArrayList<>();
                for (Location loc : network.getTerminalLocations()) {
                    terminalLocs.add(locationToString(loc));
                }
                config.set(path + ".terminals", terminalLocs);
            }

            // Save player network mappings
            for (Map.Entry<String, String> entry : playerNetworks.entrySet()) {
                config.set("player_networks." + entry.getKey(), entry.getValue());
            }

            config.save(networksFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadNetworks() {
        if (!networksFile.exists()) {
            plugin.getLogger().info("No networks file found, starting fresh.");
            return;
        }

        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(networksFile);

            // Load networks
            if (config.getConfigurationSection("networks") != null) {
                for (String networkId : config.getConfigurationSection("networks").getKeys(false)) {
                    String path = "networks." + networkId;
                    String ownerUUID = config.getString(path + ".owner");

                    if (ownerUUID == null) {
                        plugin.getLogger().warning("Skipping network " + networkId + " - no owner found");
                        continue;
                    }

                    StorageNetwork network = new StorageNetwork(networkId, ownerUUID);

                    // Load chest locations
                    List<String> chestLocs = config.getStringList(path + ".chests");
                    for (String locString : chestLocs) {
                        Location loc = stringToLocation(locString);
                        if (loc != null) {
                            network.addChest(loc);
                        }
                    }

                    // Load terminal locations
                    List<String> terminalLocs = config.getStringList(path + ".terminals");
                    for (String locString : terminalLocs) {
                        Location loc = stringToLocation(locString);
                        if (loc != null) {
                            network.addTerminal(loc);
                        }
                    }
    
                    networks.put(networkId, network);
                }
            }

            // Load player network mappings
            if (config.getConfigurationSection("player_networks") != null) {
                for (String playerUUID : config.getConfigurationSection("player_networks").getKeys(false)) {
                    String networkId = config.getString("player_networks." + playerUUID);
                    if (networkId != null && networks.containsKey(networkId)) {
                        playerNetworks.put(playerUUID, networkId);
                    }
                }
            }

            plugin.getLogger().info("Loaded " + networks.size() + " networks for " + playerNetworks.size() + " players.");

        } catch (Exception e) {
            plugin.getLogger().severe("Could not load networks file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String locationToString(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location stringToLocation(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }

        try {
            String[] parts = str.split(",");
            if (parts.length >= 4) {
                return new Location(
                        plugin.getServer().getWorld(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])
                );
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse location: " + str + " - " + e.getMessage());
        }
        return null;
    }
}