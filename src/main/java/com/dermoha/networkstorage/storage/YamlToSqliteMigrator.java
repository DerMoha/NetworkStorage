package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class YamlToSqliteMigrator {

    private final NetworkStoragePlugin plugin;
    private final File yamlNetworksFile;
    private final File yamlPlayerStateFile;
    private final SqliteNetworkStorageProvider sqliteProvider;

    public YamlToSqliteMigrator(NetworkStoragePlugin plugin,
                                File yamlNetworksFile,
                                File yamlPlayerStateFile,
                                SqliteNetworkStorageProvider sqliteProvider) {
        this.plugin = plugin;
        this.yamlNetworksFile = yamlNetworksFile;
        this.yamlPlayerStateFile = yamlPlayerStateFile;
        this.sqliteProvider = sqliteProvider;
    }

    public boolean migrate() {
        if (!sqliteProvider.isAvailable()) {
            return false;
        }
        if (!yamlNetworksFile.exists()) {
            return false;
        }
        long yamlSize = yamlNetworksFile.length();
        if (yamlSize == 0) {
            return false;
        }

        plugin.getLogger().info("Starting YAML to SQLite migration; " + yamlSize + " bytes of YAML data found.");

        Map<String, Network> networks = new java.util.LinkedHashMap<>();
        Map<Location, Network> locationIndex = new java.util.HashMap<>();
        Map<UUID, String> selectedNetworks = new java.util.HashMap<>();
        Map<UUID, String> selectedWirelessNetworks = new java.util.HashMap<>();
        OfflinePlayer[] noopLookup = new OfflinePlayer[0];

        FileConfiguration networksConfig = YamlConfiguration.loadConfiguration(yamlNetworksFile);
        ConfigurationSection networksSection = networksConfig.getConfigurationSection("networks");
        if (networksSection != null) {
            for (String networkName : networksSection.getKeys(false)) {
                ConfigurationSection netSection = networksSection.getConfigurationSection(networkName);
                if (netSection == null) {
                    continue;
                }
                try {
                    UUID owner = UUID.fromString(netSection.getString("owner"));
                    Network network = new Network(networkName, owner, plugin.getConfigManager(), plugin.getMovementEvents());
                    String description = netSection.getString("description", "");
                    if (description != null && !description.isEmpty()) {
                        network.setDescription(description);
                    }
                    for (Map<?, ?> locMap : netSection.getMapList("chests")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addChest(location);
                        }
                    }
                    for (Map<?, ?> locMap : netSection.getMapList("terminals")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addTerminal(location);
                        }
                    }
                    for (Map<?, ?> locMap : netSection.getMapList("sender-chests")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addSenderChest(location);
                        }
                    }
                    for (String trustedUuid : netSection.getStringList("trusted")) {
                        try {
                            network.addTrustedPlayer(UUID.fromString(trustedUuid));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    ConfigurationSection statsSection = netSection.getConfigurationSection("stats");
                    if (statsSection != null) {
                        for (String uuidString : statsSection.getKeys(false)) {
                            try {
                                UUID playerUUID = UUID.fromString(uuidString);
                                String statName = statsSection.getString(uuidString + ".name");
                                long deposited = Math.max(0L, statsSection.getLong(uuidString + ".deposited"));
                                long withdrawn = Math.max(0L, statsSection.getLong(uuidString + ".withdrawn"));
                                PlayerStat stat = new PlayerStat(playerUUID, statName, deposited, withdrawn);
                                network.getPlayerStats().put(playerUUID, stat);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                    networks.put(networkName, network);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Skipping migration of network '" + networkName + "': " + e.getMessage(), e);
                }
            }
        }

        if (yamlPlayerStateFile.exists()) {
            FileConfiguration playerConfig = YamlConfiguration.loadConfiguration(yamlPlayerStateFile);
            ConfigurationSection playersSection = playerConfig.getConfigurationSection("players");
            if (playersSection != null) {
                for (String uuidString : playersSection.getKeys(false)) {
                    try {
                        UUID playerId = UUID.fromString(uuidString);
                        String selected = playerConfig.getString("players." + uuidString + ".selected-owned-network");
                        String wireless = playerConfig.getString("players." + uuidString + ".selected-wireless-network");
                        if (selected != null) {
                            selectedNetworks.put(playerId, selected);
                        }
                        if (wireless != null) {
                            selectedWirelessNetworks.put(playerId, wireless);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        try {
            sqliteProvider.saveNetworks(networks.values());
            sqliteProvider.savePlayerState(selectedNetworks, selectedWirelessNetworks);

            File backupNetworks = new File(yamlNetworksFile.getParentFile(), yamlNetworksFile.getName() + ".migrated");
            File backupPlayerState = new File(yamlPlayerStateFile.getParentFile(), yamlPlayerStateFile.getName() + ".migrated");
            if (!yamlNetworksFile.renameTo(backupNetworks)) {
                plugin.getLogger().warning("Could not rename migrated networks.yml; leaving it in place.");
            }
            if (yamlPlayerStateFile.exists() && !yamlPlayerStateFile.renameTo(backupPlayerState)) {
                plugin.getLogger().warning("Could not rename migrated player-state.yml; leaving it in place.");
            }
            plugin.getLogger().info("YAML to SQLite migration complete; " + networks.size() + " networks migrated.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "YAML to SQLite migration failed; YAML data preserved.", e);
            return false;
        }
    }

    private Location deserializeLocation(Map<?, ?> locMap) {
        try {
            java.util.Map<String, Object> serializedLocation = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : locMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    serializedLocation.put(key, entry.getValue());
                }
            }
            return Location.deserialize(serializedLocation);
        } catch (Exception e) {
            return null;
        }
    }
}
