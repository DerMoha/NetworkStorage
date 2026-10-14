package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.sqlite.SqliteNetworkStorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Strict source parsing into a detached snapshot, independent of migration cutover. */
final class LegacySnapshotReader {
    private final Logger logger;
    private final NetworkAccessRules accessRules;
    private final MovementEvents movementEvents;
    LegacySnapshotReader(NetworkAccessRules accessRules, MovementEvents movementEvents, Logger logger) {
        this.logger = logger;
        this.accessRules = accessRules;
        this.movementEvents = movementEvents;
    }
    private static final Set<String> LOCATION_KEYS = Set.of("world", "world_key", "x", "y", "z", "yaw", "pitch");
    private static final Set<String> NETWORK_KEYS = Set.of(
            "owner", "description", "chests", "terminals", "sender-chests", "trusted", "trusted-expiry", "stats");

    StorageSnapshot readYamlSnapshot(File yamlNetworksFile, File yamlPlayerStateFile) {
        Map<String, Network> networks = new java.util.LinkedHashMap<>();
        Map<UUID, String> selectedNetworks = new HashMap<>();
        Map<UUID, String> selectedWirelessNetworks = new HashMap<>();

        FileConfiguration networksConfig = loadYamlStrict(yamlNetworksFile, "networks.yml");
        for (String key : networksConfig.getKeys(false)) {
            if (!"networks".equals(key)) {
                throw new StorageException("networks.yml contains unsupported root field '" + key + "'");
            }
        }
        Object rawNetworks = networksConfig.get("networks");
        ConfigurationSection networksSection = networksConfig.getConfigurationSection("networks");
        if (rawNetworks != null && networksSection == null) {
            throw new StorageException("networks.yml has malformed networks data");
        }
        if (networksSection != null) {
            for (String networkName : networksSection.getKeys(false)) {
                ConfigurationSection netSection = networksSection.getConfigurationSection(networkName);
                if (netSection == null) {
                    throw new StorageException("Network '" + networkName + "' is not a configuration section");
                }
                Object rawOwner = netSection.get("owner");
                if (!(rawOwner instanceof String ownerValue)) {
                    throw new StorageException("Network '" + networkName + "' has no string owner UUID");
                }
                if (ownerValue == null || ownerValue.isBlank()) {
                    throw new StorageException("Network '" + networkName + "' has no owner UUID");
                }
                Network network = readNetwork(networkName, UUID.fromString(ownerValue), netSection);
                if (networks.put(networkName, network) != null) {
                    throw new StorageException("Duplicate network '" + networkName + "'");
                }
            }
        }

        if (yamlPlayerStateFile.exists() && yamlPlayerStateFile.length() > 0) {
            FileConfiguration playerConfig = loadYamlStrict(yamlPlayerStateFile, "player-state.yml");
            readPlayerState(playerConfig, selectedNetworks, selectedWirelessNetworks);
        }
        pruneMissingSelections(networks, selectedNetworks, selectedWirelessNetworks);
        return StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks, Map.of());
    }

    private FileConfiguration loadYamlStrict(File file, String description) {
        YamlConfiguration configuration = new YamlConfiguration();
        if (!file.exists() || file.length() == 0) {
            return configuration;
        }
        try {
            configuration.load(file);
            return configuration;
        } catch (IOException | InvalidConfigurationException e) {
            throw new StorageException("Could not parse " + description, e);
        }
    }

    private FileConfiguration loadYamlStrict(String yaml, String description) {
        YamlConfiguration configuration = new YamlConfiguration();
        try (StringReader reader = new StringReader(yaml)) {
            configuration.load(reader);
            return configuration;
        } catch (IOException | InvalidConfigurationException e) {
            throw new StorageException("Could not parse " + description, e);
        }
    }

    StorageSnapshot readLegacySnapshot(File legacyDatabaseFile) {
        Map<String, Network> networks = new java.util.LinkedHashMap<>();
        Map<UUID, String> selectedNetworks = new HashMap<>();
        Map<UUID, String> selectedWirelessNetworks = new HashMap<>();
        SqliteNetworkStorageProvider.ensureDriver();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacyDatabaseFile.getAbsolutePath())) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT name, owner, description, data FROM networks ORDER BY name")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    UUID owner;
                    try {
                        owner = UUID.fromString(rs.getString("owner"));
                    } catch (IllegalArgumentException e) {
                        throw new StorageException("Legacy network '" + name + "' has an invalid owner UUID", e);
                    }
                    String data = rs.getString("data");
                    if (data == null || data.isBlank()) {
                        throw new StorageException("Legacy network '" + name + "' has no serialized data");
                    }
                    FileConfiguration serialized = loadYamlStrict(data, "legacy network '" + name + "'");
                    Network network = readNetwork(name, owner, serialized);
                    String description = rs.getString("description");
                    validateDescription(description, name);
                    if (description != null && !description.isEmpty()) {
                        network.setDescription(description);
                    }
                    if (networks.put(name, network) != null) {
                        throw new StorageException("Duplicate legacy network '" + name + "'");
                    }
                }
            }
            if (hasTable(connection, "player_state")) {
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT player_uuid, selected_network, selected_wireless FROM player_state")) {
                    while (rs.next()) {
                        UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                        putState(selectedNetworks, selectedWirelessNetworks, playerId,
                                rs.getString("selected_network"), rs.getString("selected_wireless"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Could not read legacy SQLite database", e);
        } catch (IllegalArgumentException e) {
            throw new StorageException("Legacy SQLite database contains invalid UUID data", e);
        }
        pruneMissingSelections(networks, selectedNetworks, selectedWirelessNetworks);
        return StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks, Map.of());
    }

    private Network readNetwork(String networkName, UUID owner, ConfigurationSection section) {
        validateNetworkName(networkName);
        if (owner == null) {
            throw new StorageException("Network '" + networkName + "' has no owner UUID");
        }
        for (String key : section.getKeys(false)) {
            if (!NETWORK_KEYS.contains(key)) {
                throw new StorageException("Network '" + networkName + "' contains unsupported field '" + key + "'");
            }
        }
        Network network = new Network(networkName, owner, accessRules, movementEvents);
        readLocations(section, "chests", networkName).forEach(network::addUnloadedChest);
        readLocations(section, "terminals", networkName).forEach(network::addUnloadedTerminal);
        readLocations(section, "sender-chests", networkName).forEach(network::addUnloadedSenderChest);

        Object trustedValue = section.get("trusted");
        if (trustedValue != null && !(trustedValue instanceof List<?>)) {
            throw new StorageException("Network '" + networkName + "' has a malformed trusted list");
        }
        Set<UUID> trustedIds = new HashSet<>();
        for (Object trustedValueEntry : trustedValue == null ? List.of() : (List<?>) trustedValue) {
            if (!(trustedValueEntry instanceof String trustedUuid)) {
                throw new StorageException("Network '" + networkName + "' has a non-string trusted UUID");
            }
            try {
                UUID trustedId = UUID.fromString(trustedUuid);
                if (!trustedIds.add(trustedId)) {
                    throw new StorageException("Network '" + networkName + "' contains duplicate trusted UUID '" + trustedUuid + "'");
                }
                network.addTrustedPlayer(trustedId);
            } catch (IllegalArgumentException e) {
                throw new StorageException("Network '" + networkName + "' has invalid trusted UUID '" + trustedUuid + "'", e);
            }
        }

        ConfigurationSection timedTrusts = section.getConfigurationSection("trusted-expiry");
        Object timedTrustValue = section.get("trusted-expiry");
        if (timedTrustValue != null && timedTrusts == null) {
            throw new StorageException("Network '" + networkName + "' has malformed timed trust data");
        }
        if (timedTrusts != null) {
            long now = System.currentTimeMillis();
            for (String uuidString : timedTrusts.getKeys(false)) {
                try {
                    UUID trustedId = UUID.fromString(uuidString);
                    Object rawExpiry = timedTrusts.get(uuidString);
                    long expiry = readExactLong(rawExpiry, "expiry for trusted UUID '" + uuidString + "'", networkName);
                    if (expiry > now) {
                        // Older YAML writers stored every trusted UUID in `trusted` and
                        // stored the expiry separately. The expiry is authoritative when
                        // both keys contain the same UUID.
                        network.addTrustedPlayerWithExpiry(trustedId, expiry);
                    } else if (trustedIds.contains(trustedId)) {
                        // Do not let an expired timed entry become permanent merely
                        // because the legacy `trusted` list still contains it.
                        network.removeTrustedPlayer(trustedId);
                    }
                } catch (IllegalArgumentException e) {
                    throw new StorageException("Network '" + networkName
                            + "' has invalid timed-trust UUID '" + uuidString + "'", e);
                }
            }
        }

        Object rawDescription = section.get("description");
        if (rawDescription != null && !(rawDescription instanceof String)) {
            throw new StorageException("Network '" + networkName + "' has a non-string description");
        }
        String description = section.getString("description", "");
        validateDescription(description, networkName);
        if (description != null && !description.isEmpty()) {
            network.setDescription(description);
        }

        ConfigurationSection stats = section.getConfigurationSection("stats");
        Object rawStats = section.get("stats");
        if (rawStats != null && stats == null) {
            throw new StorageException("Network '" + networkName + "' has malformed player statistics");
        }
        if (stats != null) {
            for (String uuidString : stats.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(uuidString);
                    ConfigurationSection statSection = stats.getConfigurationSection(uuidString);
                    if (statSection == null) {
                        throw new StorageException("Network '" + networkName + "' has malformed stats for '" + uuidString + "'");
                    }
                    for (String statKey : statSection.getKeys(false)) {
                        if (!Set.of("name", "deposited", "withdrawn").contains(statKey)) {
                            throw new StorageException("Network '" + networkName
                                    + "' has unsupported stat field '" + statKey + "'");
                        }
                    }
                    String name = readPlayerName(statSection.get("name"), networkName, uuidString);
                    long deposited = readNonNegativeLong(statSection.get("deposited"),
                            "deposited", networkName, uuidString);
                    long withdrawn = readNonNegativeLong(statSection.get("withdrawn"),
                            "withdrawn", networkName, uuidString);
                    network.restorePlayerStat( new PlayerStat(playerId, name, deposited, withdrawn));
                } catch (IllegalArgumentException e) {
                    throw new StorageException("Network '" + networkName
                            + "' has invalid player-stat UUID '" + uuidString + "'", e);
                }
            }
        }
        network.setDirty(false);
        return network;
    }

    private List<StoredLocation> readLocations(ConfigurationSection section, String key, String networkName) {
        List<StoredLocation> locations = new ArrayList<>();
        Set<StoredLocation> seenLocations = new HashSet<>();
        int index = 0;
        Object rawLocations = section.get(key);
        if (rawLocations == null) {
            return locations;
        }
        if (!(rawLocations instanceof List<?> locationList)) {
            throw new StorageException("Network '" + networkName + "' has malformed " + key + " data");
        }
        for (Object rawLocation : locationList) {
            if (!(rawLocation instanceof Map<?, ?> locationMap)) {
                throw new StorageException("Network '" + networkName + "' has malformed " + key
                        + " entry at index " + index);
            }
            StoredLocation location = deserializeLocation(locationMap, networkName, key + "[" + index + "]");
            if (!seenLocations.add(location)) {
                throw new StorageException("Network '" + networkName + "' contains a duplicate " + key + " location");
            }
            locations.add(location);
            index++;
        }
        return locations;
    }

    private StoredLocation deserializeLocation(Map<?, ?> locationMap, String networkName, String locationType) {
        try {
            for (var entry : locationMap.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !LOCATION_KEYS.contains(key)) {
                    throw new IllegalArgumentException("unsupported location key");
                }
                if (("yaw".equals(key) || "pitch".equals(key))
                        && (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue()))) {
                    throw new IllegalArgumentException("invalid location orientation");
                }
            }
            Object rawWorld = locationMap.containsKey("world") ? locationMap.get("world") : locationMap.get("world_key");
            if (!(rawWorld instanceof String world) || world.isBlank()) {
                throw new IllegalArgumentException("location is missing a world identifier");
            }
            if (locationMap.containsKey("world") && locationMap.containsKey("world_key")
                    && !world.equals(locationMap.get("world_key"))) {
                throw new IllegalArgumentException("location contains conflicting world identifiers");
            }
            int x = Math.toIntExact(StorageValues.exactLong(locationMap.get("x"), "location x"));
            int y = Math.toIntExact(StorageValues.exactLong(locationMap.get("y"), "location y"));
            int z = Math.toIntExact(StorageValues.exactLong(locationMap.get("z"), "location z"));
            if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L) {
                throw new IllegalArgumentException("coordinates are outside safe world bounds");
            }
            // Import detached positions. The runtime resolves worlds when they are available.
            return new StoredLocation(world, x, y, z);
        } catch (Exception e) {
            throw new StorageException("Invalid " + locationType + " location in network '" + networkName + "'", e);
        }
    }

    private void readPlayerState(FileConfiguration config,
                                  Map<UUID, String> selectedNetworks,
                                  Map<UUID, String> selectedWirelessNetworks) {
        for (String key : config.getKeys(false)) {
            if (!"players".equals(key)) {
                throw new StorageException("player-state.yml contains unsupported root field '" + key + "'");
            }
        }
        ConfigurationSection players = config.getConfigurationSection("players");
        Object rawPlayers = config.get("players");
        if (rawPlayers != null && players == null) {
            throw new StorageException("Player-state YAML has malformed players data");
        }
        if (players == null) {
            return;
        }
        for (String uuidString : players.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(uuidString);
                ConfigurationSection player = players.getConfigurationSection(uuidString);
                if (player == null) {
                    throw new StorageException("Player-state entry '" + uuidString + "' is not a section");
                }
                for (String playerKey : player.getKeys(false)) {
                    if (!Set.of("selected-owned-network", "selected-wireless-network").contains(playerKey)) {
                        throw new StorageException("Player-state entry '" + uuidString
                                + "' has unsupported field '" + playerKey + "'");
                    }
                }
                putState(selectedNetworks, selectedWirelessNetworks, playerId,
                        readOptionalString(player.get("selected-owned-network"), uuidString,
                                "selected-owned-network"),
                        readOptionalString(player.get("selected-wireless-network"), uuidString,
                                "selected-wireless-network"));
            } catch (IllegalArgumentException e) {
                throw new StorageException("Invalid player-state UUID '" + uuidString + "'", e);
            }
        }
    }

    private void pruneMissingSelections(Map<String, Network> networks,
                                        Map<UUID, String> selected,
                                        Map<UUID, String> wireless) {
        pruneMissingSelections(networks, selected, "owned");
        pruneMissingSelections(networks, wireless, "wireless");
    }

    private void pruneMissingSelections(Map<String, Network> networks,
                                        Map<UUID, String> selections, String kind) {
        selections.entrySet().removeIf(entry -> {
            if (networks.containsKey(entry.getValue())) {
                return false;
            }
            logger.warning("Cleared stale " + kind + " selection for " + entry.getKey()
                    + ": network '" + entry.getValue() + "' no longer exists");
            return true;
        });
    }

    private void putState(Map<UUID, String> selectedNetworks,
                          Map<UUID, String> selectedWirelessNetworks,
                          UUID playerId,
                          String selected,
                          String wireless) {
        if (selected != null && !selected.isBlank()) {
            selectedNetworks.put(playerId, selected);
        }
        if (wireless != null && !wireless.isBlank()) {
            selectedWirelessNetworks.put(playerId, wireless);
        }
    }

    private void validateNetworkName(String networkName) {
        if (!StorageValues.isValidNetworkName(networkName)) {
            throw new StorageException("Invalid network name '" + networkName + "'");
        }
    }

    private void validateDescription(String description, String networkName) {
        if (description != null && description.length() > Network.MAX_DESCRIPTION_LENGTH) {
            throw new StorageException("Network '" + networkName + "' description is too long to represent safely");
        }
    }

    private String readPlayerName(Object rawName, String networkName, String playerId) {
        if (!(rawName instanceof String name)
                || (!"Unknown Player".equals(name.trim()) && !StorageValues.isValidPlayerName(name))) {
            throw new StorageException("Network '" + networkName + "' has an invalid player name for stat '" + playerId + "'");
        }
        return name.trim();
    }

    private long readNonNegativeLong(Object rawValue, String field, String networkName, String playerId) {
        long value = readExactLong(rawValue, field + " value for stat '" + playerId + "'", networkName);
        if (value < 0) {
            throw new StorageException("Network '" + networkName + "' has a negative " + field
                    + " value for stat '" + playerId + "'");
        }
        return value;
    }

    private long readExactLong(Object rawValue, String field, String networkName) {
        try {
            return StorageValues.exactLong(rawValue, "Network '" + networkName + "' " + field);
        } catch (StorageException e) {
            throw e;
        }
    }

    private String readOptionalString(Object rawValue, String playerId, String field) {
        if (rawValue == null) {
            return null;
        }
        if (!(rawValue instanceof String value)) {
            throw new StorageException("Player-state entry '" + playerId + "' has a non-string " + field);
        }
        return value;
    }

    private boolean hasTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
