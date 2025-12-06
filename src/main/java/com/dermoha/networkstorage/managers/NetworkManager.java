package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
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
    private boolean dirty = false;
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    // Track which network each player is currently using
    private final Map<UUID, String> activeNetworks = new HashMap<>();

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
                globalNetwork.setNetworkManager(this);
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
                        network.setNetworkManager(this);

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

                        ConfigurationSection statsSection = netSection.getConfigurationSection("stats");
                        if (statsSection != null) {
                            for (String uuidString : statsSection.getKeys(false)) {
                                UUID playerUUID = UUID.fromString(uuidString);
                                String name = statsSection.getString(uuidString + ".name");

                                if (name == null || name.isEmpty()) {
                                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
                                    if (offlinePlayer != null && offlinePlayer.getName() != null) {
                                        name = offlinePlayer.getName();
                                    } else {
                                        name = "Unknown Player";
                                    }
                                }

                                long deposited = statsSection.getLong(uuidString + ".deposited");
                                long withdrawn = statsSection.getLong(uuidString + ".withdrawn");

                                PlayerStat stat = new PlayerStat(playerUUID, name, deposited, withdrawn);
                                network.getPlayerStats().put(playerUUID, stat);
                            }
                        }

                        networks.put(networkName, network);
                        network.rebuildCache(); // Build the cache after loading
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not load network '" + networkName + "': " + e.getMessage());
                    }
                }
            }
        }
    }

    public void saveNetworks() {
        if (!dirty) {
            return;
        }
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

            for (PlayerStat stat : network.getPlayerStats().values()) {
                String statPath = path + ".stats." + stat.getPlayerUUID().toString();
                newConfig.set(statPath + ".name", stat.getPlayerName());
                newConfig.set(statPath + ".deposited", stat.getItemsDeposited());
                newConfig.set(statPath + ".withdrawn", stat.getItemsWithdrawn());
            }
        }
        try {
            newConfig.save(networksFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks to " + networksFile);
            e.printStackTrace();
        }
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void createNetwork(Player player, String networkName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage("Cannot create a network in GLOBAL mode.");
            return;
        }

        // Check max networks limit
        int maxNetworks = plugin.getConfigManager().getMaxNetworksPerPlayer();
        if (maxNetworks > 0) {
            List<Network> playerNetworks = getPlayerNetworks(player);
            if (playerNetworks.size() >= maxNetworks) {
                player.sendMessage("You have reached the maximum number of networks (" + maxNetworks + ").");
                return;
            }
        }

        if (networks.containsKey(networkName)) {
            player.sendMessage("A network with that name already exists.");
            return;
        }

        Network network = new Network(networkName, player.getUniqueId());
        network.setNetworkManager(this);
        networks.put(networkName, network);

        // Set as active if this is their first network
        if (getPlayerNetworks(player).size() == 1) {
            activeNetworks.put(player.getUniqueId(), networkName);
        }

        markDirty();
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

    /**
     * Get all networks owned by a player
     */
    public List<Network> getPlayerNetworks(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return Collections.singletonList(networks.get(GLOBAL_NETWORK_NAME));
        }
        List<Network> playerNetworks = new ArrayList<>();
        for (Network network : networks.values()) {
            if (network.getOwner().equals(player.getUniqueId())) {
                playerNetworks.add(network);
            }
        }
        return playerNetworks;
    }

    /**
     * Get the player's currently active network
     * If no active network is set, returns their first network or null
     */
    public Network getPlayerNetwork(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }

        // Check if player has an active network set
        String activeNetworkName = activeNetworks.get(player.getUniqueId());
        if (activeNetworkName != null) {
            Network activeNetwork = networks.get(activeNetworkName);
            if (activeNetwork != null && activeNetwork.getOwner().equals(player.getUniqueId())) {
                return activeNetwork;
            }
        }

        // No active network or it's invalid, return first owned network
        for (Network network : networks.values()) {
            if (network.getOwner().equals(player.getUniqueId())) {
                // Set this as active for next time
                activeNetworks.put(player.getUniqueId(), network.getName());
                return network;
            }
        }
        return null;
    }

    /**
     * Set a player's active network
     */
    public boolean setActiveNetwork(Player player, String networkName) {
        Network network = networks.get(networkName);
        if (network == null) {
            return false;
        }
        if (!network.getOwner().equals(player.getUniqueId()) && !player.hasPermission("networkstorage.admin")) {
            return false;
        }
        activeNetworks.put(player.getUniqueId(), networkName);
        return true;
    }

    /**
     * Get a specific network by name (if player owns it)
     */
    public Network getPlayerNetworkByName(Player player, String networkName) {
        Network network = networks.get(networkName);
        if (network != null && network.getOwner().equals(player.getUniqueId())) {
            return network;
        }
        return null;
    }

    /**
     * Delete a network (if player owns it)
     */
    public boolean deleteNetwork(Player player, String networkName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return false;
        }

        Network network = networks.get(networkName);
        if (network == null) {
            return false;
        }

        if (!network.getOwner().equals(player.getUniqueId()) && !player.hasPermission("networkstorage.admin")) {
            return false;
        }

        // Remove from networks
        networks.remove(networkName);

        // If this was the active network, clear it
        if (networkName.equals(activeNetworks.get(player.getUniqueId()))) {
            activeNetworks.remove(player.getUniqueId());
        }

        markDirty();
        return true;
    }

    /**
     * Rename a network (if player owns it)
     */
    public boolean renameNetwork(Player player, String oldName, String newName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return false;
        }

        // Check if new name already exists
        if (networks.containsKey(newName)) {
            player.sendMessage(plugin.getLanguageManager().getMessage("network.rename.already_exists").replace("%s", newName));
            return false;
        }

        Network network = networks.get(oldName);
        if (network == null) {
            return false;
        }

        if (!network.getOwner().equals(player.getUniqueId()) && !player.hasPermission("networkstorage.admin")) {
            return false;
        }

        // Update the network name internally
        network.setName(newName);

        // Remove old entry and add new one
        networks.remove(oldName);
        networks.put(newName, network);

        // Update active network reference if this was active
        if (oldName.equals(activeNetworks.get(player.getUniqueId()))) {
            activeNetworks.put(player.getUniqueId(), newName);
        }

        markDirty();
        return true;
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
            network.setNetworkManager(this);
            networks.put(networkName, network);

            // Set as active network
            activeNetworks.put(player.getUniqueId(), networkName);

            markDirty();
        }
        return network;
    }
}
