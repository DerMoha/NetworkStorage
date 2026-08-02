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

public class YamlToSqliteMigrator {

    private final NetworkStoragePlugin plugin;
    private final NetworkAccessRules accessRules;
    private final MovementEvents movementEvents;
    private final File dataFolder;
    private final Logger logger;
    private final File yamlNetworksFile;
    private final File yamlPlayerStateFile;
    private final File targetDatabaseFile;
    private static final Set<String> LOCATION_KEYS = Set.of("world", "world_key", "x", "y", "z", "yaw", "pitch");
    private static final Set<String> NETWORK_KEYS = Set.of(
            "owner", "description", "chests", "terminals", "sender-chests", "trusted", "trusted-expiry", "stats");

    public YamlToSqliteMigrator(NetworkStoragePlugin plugin,
                                File yamlNetworksFile,
                                File yamlPlayerStateFile,
                                File targetDatabaseFile) {
        this.plugin = plugin;
        this.accessRules = plugin.getConfigManager();
        this.movementEvents = plugin.getMovementEvents();
        this.dataFolder = plugin.getDataFolder();
        this.logger = plugin.getLogger();
        this.yamlNetworksFile = yamlNetworksFile;
        this.yamlPlayerStateFile = yamlPlayerStateFile;
        this.targetDatabaseFile = targetDatabaseFile;
    }

    /** Constructor used by migration tests and offline migration tooling. */
    public YamlToSqliteMigrator(NetworkAccessRules accessRules,
                                MovementEvents movementEvents,
                                File dataFolder,
                                File yamlNetworksFile,
                                File yamlPlayerStateFile,
                                File targetDatabaseFile,
                                Logger logger) {
        this.plugin = null;
        this.accessRules = accessRules;
        this.movementEvents = movementEvents;
        this.dataFolder = dataFolder;
        this.logger = logger == null ? Logger.getLogger(YamlToSqliteMigrator.class.getName()) : logger;
        this.yamlNetworksFile = yamlNetworksFile;
        this.yamlPlayerStateFile = yamlPlayerStateFile;
        this.targetDatabaseFile = targetDatabaseFile;
    }

    public void migrateYaml() {
        if ((!yamlNetworksFile.exists() || yamlNetworksFile.length() == 0)
                && (!yamlPlayerStateFile.exists() || yamlPlayerStateFile.length() == 0)) {
            return;
        }
        try {
            StorageSnapshot snapshot = readYamlSnapshot();
            String sourceHash = hashFiles(yamlNetworksFile, yamlPlayerStateFile);
            migrateSnapshot("yaml-" + System.currentTimeMillis(), sourceHash, snapshot,
                    List.of(yamlNetworksFile, yamlPlayerStateFile), null);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("Could not strictly parse YAML migration source", e);
        }
    }

    public void migrateLegacyDatabase(File legacyDatabaseFile) {
        try {
            StorageSnapshot snapshot = readLegacySnapshot(legacyDatabaseFile);
            migrateSnapshot("legacy-sqlite-" + System.currentTimeMillis(),
                    hashDatabaseFamily(legacyDatabaseFile), snapshot,
                    List.of(legacyDatabaseFile), legacyDatabaseFile);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("Could not strictly parse legacy SQLite migration source", e);
        }
    }

    private StorageSnapshot readYamlSnapshot() {
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
        return StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks);
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

    private StorageSnapshot readLegacySnapshot(File legacyDatabaseFile) {
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
        return StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks);
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
        readLocations(section, "chests", networkName).forEach(network::addChest);
        readLocations(section, "terminals", networkName).forEach(network::addTerminal);
        readLocations(section, "sender-chests", networkName).forEach(network::addSenderChest);

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
                    network.getPlayerStats().put(playerId, new PlayerStat(playerId, name, deposited, withdrawn));
                } catch (IllegalArgumentException e) {
                    throw new StorageException("Network '" + networkName
                            + "' has invalid player-stat UUID '" + uuidString + "'", e);
                }
            }
        }
        network.setDirty(false);
        return network;
    }

    private List<Location> readLocations(ConfigurationSection section, String key, String networkName) {
        List<Location> locations = new ArrayList<>();
        Set<Location> seenLocations = new HashSet<>();
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
            Location location = deserializeLocation(locationMap, networkName, key + "[" + index + "]");
            if (!seenLocations.add(location)) {
                throw new StorageException("Network '" + networkName + "' contains a duplicate " + key + " location");
            }
            locations.add(location);
            index++;
        }
        return locations;
    }

    private Location deserializeLocation(Map<?, ?> locationMap, String networkName, String locationType) {
        try {
            Map<String, Object> serialized = new HashMap<>();
            for (Map.Entry<?, ?> entry : locationMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("non-string location key");
                }
                if (!LOCATION_KEYS.contains(key)) {
                    throw new IllegalArgumentException("unsupported location key '" + key + "'");
                }
                if (("x".equals(key) || "y".equals(key) || "z".equals(key)
                        || "yaw".equals(key) || "pitch".equals(key))
                        && !(entry.getValue() instanceof Number)) {
                    throw new IllegalArgumentException("location coordinate is not numeric");
                }
                if (("world".equals(key) || "world_key".equals(key))
                        && !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException("location world is not a string");
                }
                serialized.put(key, entry.getValue());
            }
            if ((!serialized.containsKey("world") && !serialized.containsKey("world_key"))
                    || !serialized.keySet().containsAll(Set.of("x", "y", "z"))) {
                throw new IllegalArgumentException("location is missing world or coordinates");
            }
            if (serialized.containsKey("world") && serialized.containsKey("world_key")
                    && !serialized.get("world").equals(serialized.get("world_key"))) {
                throw new IllegalArgumentException("location contains conflicting world identifiers");
            }
            Location location = Location.deserialize(serialized);
            if (location == null || location.getWorld() == null
                    || !Double.isFinite(location.getX())
                    || !Double.isFinite(location.getY())
                    || !Double.isFinite(location.getZ())
                    || location.getX() != Math.rint(location.getX())
                    || location.getY() != Math.rint(location.getY())
                    || location.getZ() != Math.rint(location.getZ())) {
                throw new IllegalArgumentException("invalid coordinates or world");
            }
            World world = Bukkit.getWorld(location.getWorld().getName());
            if (world == null) {
                throw new IllegalArgumentException("world is not loaded");
            }
            if (Math.abs(location.getX()) > 3.0E7
                    || Math.abs(location.getZ()) > 3.0E7
                    || location.getY() < world.getMinHeight() - 1024
                    || location.getY() > world.getMaxHeight() + 1024) {
                throw new IllegalArgumentException("coordinates are outside safe world bounds");
            }
            return new Location(world, location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
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

    private void migrateSnapshot(String migrationId,
                                 String sourceHash,
                                 StorageSnapshot snapshot,
                                 List<File> sourceFiles,
                                 File legacyDatabase) {
        File dataFolder = this.dataFolder;
        File lockFile = new File(dataFolder, "migration.lock");
        if (lockFile.exists()) {
            throw new StorageException("migration.lock exists; refusing to use an unverified migration");
        }
        try {
            Files.createFile(lockFile.toPath());
        } catch (IOException e) {
            throw new StorageException("Could not create migration.lock", e);
        }

        File temporaryDatabase = new File(dataFolder,
                targetDatabaseFile.getName() + ".migrating-" + UUID.randomUUID());
        SqliteNetworkStorageProvider temporaryProvider = null;
        boolean promoted = false;
        try {
            copySourceBackups(sourceFiles, legacyDatabase);
            if (!sourceHash.equals(hashMigrationSource(sourceFiles, legacyDatabase))) {
                throw new StorageException("Migration source changed while it was being prepared");
            }

            temporaryProvider = plugin == null
                    ? new SqliteNetworkStorageProvider(temporaryDatabase)
                    : new SqliteNetworkStorageProvider(plugin, temporaryDatabase);
            temporaryProvider.initialize();
            if (!temporaryProvider.setMigrationInProgress(migrationId, sourceHash)) {
                throw new StorageException("Could not mark temporary SQLite migration in progress");
            }
            if (!temporaryProvider.saveSnapshot(snapshot)) {
                throw new StorageException("Temporary SQLite snapshot write failed");
            }
            temporaryProvider.verifySnapshot(snapshot);
            if (!temporaryProvider.setMigrationMetadata(migrationId, sourceHash, snapshot)) {
                throw new StorageException("SQLite migration metadata write failed");
            }
            temporaryProvider.verifySnapshot(snapshot);
            temporaryProvider.verifyDatabase();
            temporaryProvider.shutdown();
            temporaryProvider = null;

            if (!sourceHash.equals(hashMigrationSource(sourceFiles, legacyDatabase))) {
                throw new StorageException("Migration source changed before database promotion");
            }

            promoteDatabase(temporaryDatabase, legacyDatabase);
            promoted = true;
            if (legacyDatabase == null) {
                archiveSources(sourceFiles);
            }
            logger.info("SQLite migration complete: " + snapshot.networkCount()
                    + " networks, digest=" + snapshot.digest());
        } catch (RuntimeException | IOException e) {
            logger.log(Level.SEVERE,
                    "SQLite migration failed; original data was preserved and the plugin will not enable", e);
            throw e instanceof StorageException
                    ? (StorageException) e
                    : new StorageException("SQLite migration failed", e);
        } finally {
            if (temporaryProvider != null) {
                temporaryProvider.shutdown();
            }
            if (!promoted) {
                preserveFailedTemporaryDatabase(temporaryDatabase);
                logger.severe("Migration lock retained at " + lockFile.getAbsolutePath()
                        + "; inspect the preserved temporary database before retrying.");
            } else {
                try {
                    Files.deleteIfExists(lockFile.toPath());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not remove migration.lock after success", e);
                }
            }
            if (promoted) {
                try {
                    Files.deleteIfExists(temporaryDatabase.toPath());
                    Files.deleteIfExists(Path.of(temporaryDatabase.getAbsolutePath() + "-wal"));
                    Files.deleteIfExists(Path.of(temporaryDatabase.getAbsolutePath() + "-shm"));
                } catch (IOException cleanupFailure) {
                    logger.log(Level.WARNING, "Could not clean up temporary migration database", cleanupFailure);
                }
            }
        }
    }

    private void copySourceBackup(File source) {
        File backup = new File(source.getParentFile(), source.getName()
                + ".pre-sqlite-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        try {
            Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new StorageException("Could not back up migration source " + source.getName(), e);
        }
    }

    private void copySourceBackups(List<File> sourceFiles, File legacyDatabase) {
        if (legacyDatabase != null) {
            copyDatabaseFamilyBackup(legacyDatabase);
            return;
        }
        for (File source : sourceFiles) {
            if (source.exists()) {
                copySourceBackup(source);
            }
        }
    }

    private void copyDatabaseFamilyBackup(File database) {
        File backup = new File(database.getParentFile(), database.getName()
                + ".pre-sqlite-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        try {
            copyIfExists(database, backup);
            copyIfExists(new File(database.getAbsolutePath() + "-wal"), new File(backup.getAbsolutePath() + "-wal"));
            copyIfExists(new File(database.getAbsolutePath() + "-shm"), new File(backup.getAbsolutePath() + "-shm"));
        } catch (IOException e) {
            throw new StorageException("Could not back up legacy SQLite database family", e);
        }
    }

    private void copyIfExists(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void promoteDatabase(File temporaryDatabase, File legacyDatabase) throws IOException {
        File target = targetDatabaseFile;
        File oldBackup = null;
        if (target.exists() || sidecarExists(target)) {
            String suffix = legacyDatabase == null ? "empty" : "legacy";
            oldBackup = new File(target.getParentFile(), target.getName()
                    + "." + suffix + "-" + System.currentTimeMillis() + ".db");
            moveDatabaseFamily(target, oldBackup);
        }
        try {
            moveDatabaseFamily(temporaryDatabase, target);
        } catch (IOException promotionFailure) {
            if (oldBackup != null) {
                try {
                    deleteDatabaseFamily(target);
                    moveDatabaseFamily(oldBackup, target);
                } catch (IOException restoreFailure) {
                    promotionFailure.addSuppressed(restoreFailure);
                }
            }
            throw promotionFailure;
        }
    }

    private void preserveFailedTemporaryDatabase(File temporaryDatabase) {
        if (!temporaryDatabase.exists() && !sidecarExists(temporaryDatabase)) {
            return;
        }
        File failedDatabase = new File(temporaryDatabase.getParentFile(),
                temporaryDatabase.getName() + ".failed-" + UUID.randomUUID() + ".db");
        try {
            moveDatabaseFamily(temporaryDatabase, failedDatabase);
        } catch (IOException e) {
            logger.log(Level.SEVERE,
                    "Could not rename failed temporary database; it remains at " + temporaryDatabase, e);
        }
    }

    private boolean sidecarExists(File database) {
        return new File(database.getAbsolutePath() + "-wal").exists()
                || new File(database.getAbsolutePath() + "-shm").exists();
    }

    private void moveDatabaseFamily(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        moveIfExists(new File(source.getAbsolutePath() + "-wal"),
                new File(destination.getAbsolutePath() + "-wal"));
        moveIfExists(new File(source.getAbsolutePath() + "-shm"),
                new File(destination.getAbsolutePath() + "-shm"));
    }

    private void moveIfExists(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void deleteDatabaseFamily(File database) throws IOException {
        Files.deleteIfExists(database.toPath());
        Files.deleteIfExists(Path.of(database.getAbsolutePath() + "-wal"));
        Files.deleteIfExists(Path.of(database.getAbsolutePath() + "-shm"));
    }

    private void archiveSources(List<File> sourceFiles) {
        for (File source : sourceFiles) {
            if (!source.exists()) {
                continue;
            }
            File archive = new File(source.getParentFile(), source.getName()
                    + ".migrated-" + System.currentTimeMillis());
            try {
                Files.move(source.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "Could not archive migration source " + source.getName()
                                + "; it was retained and will not be re-imported", e);
            }
        }
    }

    private String hashFiles(File... files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (File file : files) {
                if (!file.exists()) {
                    continue;
                }
                digest.update(file.getName().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file.toPath()));
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new StorageException("Could not hash migration source", e);
        }
    }

    private String hashMigrationSource(List<File> sourceFiles, File legacyDatabase) {
        return legacyDatabase == null ? hashFiles(sourceFiles.toArray(new File[0])) : hashDatabaseFamily(legacyDatabase);
    }

    private String hashDatabaseFamily(File database) {
        return hashFiles(database, new File(database.getAbsolutePath() + "-wal"),
                new File(database.getAbsolutePath() + "-shm"));
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
