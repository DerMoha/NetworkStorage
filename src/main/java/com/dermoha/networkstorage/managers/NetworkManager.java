package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class NetworkManager {

    private final NetworkStoragePlugin plugin;
    private final Map<String, Network> networks = new HashMap<>();
    private final File networksFile;
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public NetworkManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.networksFile = new File(plugin.getDataFolder(), "networks.yml");
        if (!networksFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                networksFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create networks.yml: " + e.getMessage());
            }
        }
        loadNetworks();
    }

    private void loadNetworks() {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            if (!networks.containsKey(GLOBAL_NETWORK_NAME)) {
                Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER);
                networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
            }
        }
        FileConfiguration networksConfig = YamlConfiguration.loadConfiguration(networksFile);
        ConfigurationSection networksSection = networksConfig.getConfigurationSection("networks");
        if (networksSection != null) {
            for (String networkName : networksSection.getKeys(false)) {
                ConfigurationSection netSection = networksSection.getConfigurationSection(networkName);
                if (netSection != null) {
                    try {
                        UUID owner = UUID.fromString(netSection.getString("owner"));
                        Network network = new Network(networkName, owner);

                        List<Map<?, ?>> chestLocationsMaps = netSection.getMapList("chests");
                        for (Map<?, ?> locMap : chestLocationsMaps) {
                            network.addChest(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<Map<?, ?>> terminalLocationsMaps = netSection.getMapList("terminals");
                        for (Map<?, ?> locMap : terminalLocationsMaps) {
                            network.addTerminal(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<Map<?, ?>> senderChestLocationsMaps = netSection.getMapList("sender-chests");
                        for (Map<?, ?> locMap : senderChestLocationsMaps) {
                            network.addSenderChest(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<String> trustedUuids = netSection.getStringList("trusted");
                        trustedUuids.stream().map(UUID::fromString).forEach(network::addTrustedPlayer);

                        // Load player stats
                        ConfigurationSection statsSection = netSection.getConfigurationSection("stats");
                        if (statsSection != null) {
                            for (String uuidString : statsSection.getKeys(false)) {
                                UUID playerUUID = UUID.fromString(uuidString);
                                String name = statsSection.getString(uuidString + ".name");
                                long deposited = statsSection.getLong(uuidString + ".deposited");
                                long withdrawn = statsSection.getLong(uuidString + ".withdrawn");

                                PlayerStat stat = new PlayerStat(playerUUID, name, deposited, withdrawn);
                                network.getPlayerStats().put(playerUUID, stat);
                            }
                        }

                        networks.put(networkName, network);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not load network '" + networkName + "': " + e.getMessage());
                    }
                }
            }
        }
    }

    public void saveNetworks() {
        FileConfiguration newConfig = new YamlConfiguration();
        for (Network network : networks.values()) {
            String path = "networks." + network.getName();
            newConfig.set(path + ".owner", network.getOwner().toString());

            List<Map<String, Object>> serializedChests = new ArrayList<>();
            for (Location loc : network.getChestLocations()) {
                serializedChests.add(loc.serialize());
            }
            newConfig.set(path + ".chests", serializedChests);

            List<Map<String, Object>> serializedTerminals = new ArrayList<>();
            for (Location loc : network.getTerminalLocations()) {
                serializedTerminals.add(loc.serialize());
            }
            newConfig.set(path + ".terminals", serializedTerminals);

            List<Map<String, Object>> serializedSenderChests = new ArrayList<>();
            for (Location loc : network.getSenderChestLocations()) {
                serializedSenderChests.add(loc.serialize());
            }
            newConfig.set(path + ".sender-chests", serializedSenderChests);

            newConfig.set(path + ".trusted", network.getTrustedPlayers().stream().map(UUID::toString).collect(Collectors.toList()));

            // Save player stats
            for (PlayerStat stat : network.getPlayerStats().values()) {
                String statPath = path + ".stats." + stat.getPlayerUUID().toString();
                newConfig.set(statPath + ".name", stat.getPlayerName());
                newConfig.set(statPath + ".deposited", stat.getItemsDeposited());
                newConfig.set(statPath + ".withdrawn", stat.getItemsWithdrawn());
            }
        }
        try {
            newConfig.save(networksFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks to " + networksFile);
            e.printStackTrace();
        }
    }

    public void createNetwork(Player player, String networkName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage("Cannot create a network in GLOBAL mode.");
            return;
        }
        if (networks.containsKey(networkName)) {
            player.sendMessage("A network with that name already exists.");
            return;
        }
        Network network = new Network(networkName, player.getUniqueId());
        networks.put(networkName, network);
        saveNetworks();
        player.sendMessage("Network '" + networkName + "' created successfully.");
    }

    public void editNetwork(Player player, String networkName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage("Cannot edit a network in GLOBAL mode.");
            return;
        }
        if (!networks.containsKey(networkName)) {
            player.sendMessage("Network '" + networkName + "' not found.");
            return;
        }
        player.sendMessage("Network editing is not yet implemented.");
    }

    public void renameNetwork(Player player, String oldName, String newName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage("Cannot rename a network in GLOBAL mode.");
            return;
        }
        if (!networks.containsKey(oldName)) {
            player.sendMessage("Network '" + oldName + "' not found.");
            return;
        }
        if (networks.containsKey(newName)) {
            player.sendMessage("A network with the name '" + newName + "' already exists.");
            return;
        }
        Network network = networks.get(oldName);

        if (!network.getOwner().equals(player.getUniqueId()) && !player.hasPermission("networkstorage.admin")) {
             player.sendMessage("You do not have permission to rename this network.");
             return;
        }

        networks.remove(oldName);
        network.setName(newName);
        networks.put(newName, network);
        saveNetworks();
        player.sendMessage("Network '" + oldName + "' has been renamed to '" + newName + "'.");
    }

    public Network getNetwork(String name) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        return networks.get(name);
    }

    public Collection<Network> getAllNetworks() {
        return networks.values();
    }

    public Network getNetworkByLocation(Location location) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = networks.get(GLOBAL_NETWORK_NAME);
            if (globalNetwork != null && (globalNetwork.isChestInNetwork(location) || globalNetwork.isTerminalInNetwork(location))) {
                return globalNetwork;
            }
            return null;
        }
        for (Network network : networks.values()) {
            if (network.isChestInNetwork(location) || network.isTerminalInNetwork(location)) {
                return network;
            }
        }
        return null;
    }

    public Network getPlayerNetwork(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        for (Network network : networks.values()) {
            if (network.getOwner().equals(player.getUniqueId())) {
                return network;
            }
        }
        return null;
    }

    public Network getOrCreatePlayerNetwork(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        Network network = getPlayerNetwork(player);
        if (network == null) {
            String networkName = player.getName() + "'s Network";
            if (networks.containsKey(networkName) && !networks.get(networkName).getOwner().equals(player.getUniqueId())) {
                player.sendMessage("A network with your default name already exists, but you are not the owner.");
                return null;
            }
            network = new Network(networkName, player.getUniqueId());
            networks.put(networkName, network);
            saveNetworks();
        }
        return network;
    }
}
