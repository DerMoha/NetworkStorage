package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
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

    public NetworkManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.networksFile = new File(plugin.getDataFolder(), "networks.yml");
        if (!networksFile.exists()) {
            plugin.saveResource("networks.yml", false);
        }
        loadNetworks();
    }

    private void loadNetworks() {
        FileConfiguration networksConfig = YamlConfiguration.loadConfiguration(networksFile);
        ConfigurationSection networksSection = networksConfig.getConfigurationSection("networks");
        if (networksSection != null) {
            for (String networkName : networksSection.getKeys(false)) {
                ConfigurationSection netSection = networksSection.getConfigurationSection(networkName);
                if (netSection != null) {
                    try {
                        UUID owner = UUID.fromString(netSection.getString("owner"));
                        Network network = new Network(networkName, owner);

                        List<?> chestLocationsRaw = netSection.getList("chests", new ArrayList<>());
                        for (Object locObj : chestLocationsRaw) {
                            if (locObj instanceof Location) {
                                network.addChest((Location) locObj);
                            }
                        }

                        List<?> terminalLocationsRaw = netSection.getList("terminals", new ArrayList<>());
                        for (Object locObj : terminalLocationsRaw) {
                            if (locObj instanceof Location) {
                                network.addTerminal((Location) locObj);
                            }
                        }

                        List<String> trustedUuids = netSection.getStringList("trusted");
                        trustedUuids.stream().map(UUID::fromString).forEach(network::addTrustedPlayer);

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
            newConfig.set(path + ".chests", new ArrayList<>(network.getChestLocations()));
            newConfig.set(path + ".terminals", new ArrayList<>(network.getTerminalLocations()));
            newConfig.set(path + ".trusted", network.getTrustedPlayers().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        try {
            newConfig.save(networksFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks to " + networksFile);
            e.printStackTrace();
        }
    }

    public void createNetwork(Player player, String networkName) {
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
        if (!networks.containsKey(networkName)) {
            player.sendMessage("Network '" + networkName + "' not found.");
            return;
        }
        player.sendMessage("Network editing is not yet implemented.");
    }

    public void renameNetwork(Player player, String oldName, String newName) {
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
        return networks.get(name);
    }

    public Collection<Network> getAllNetworks() {
        return networks.values();
    }

    public Network getNetworkByLocation(Location location) {
        for (Network network : networks.values()) {
            if (network.isChestInNetwork(location) || network.isTerminalInNetwork(location)) {
                return network;
            }
        }
        return null;
    }

    public Network getPlayerNetwork(Player player) {
        for (Network network : networks.values()) {
            if (network.getOwner().equals(player.getUniqueId())) {
                return network;
            }
        }
        return null;
    }

    public Network getOrCreatePlayerNetwork(Player player) {
        Network network = getPlayerNetwork(player);
        if (network == null) {
            String networkName = player.getName() + "'s Network";
            // Avoid creating a new network if one with the default name already exists but is owned by someone else
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
