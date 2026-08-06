package com.dermoha.networkstorage.storage.sqlite;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkAccessRules;
import com.dermoha.networkstorage.storage.NetworkStorageProvider;
import com.dermoha.networkstorage.storage.MovementEvents;
import com.dermoha.networkstorage.storage.StorageException;
import com.dermoha.networkstorage.storage.StorageSnapshot;
import com.dermoha.networkstorage.storage.StorageValues;
import com.dermoha.networkstorage.storage.StoredLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SqliteNetworkStorageProvider implements NetworkStorageProvider {


    public enum DatabaseLayout {
        ABSENT,
        EMPTY,
        PACKED_LEGACY,
        NORMALIZED,
        UNKNOWN
    }

    private final NetworkStoragePlugin plugin;
    private final NetworkAccessRules accessRules;
    private final File databaseFile;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Connection connection;
    private volatile boolean available;
    private volatile int schemaVersion;
    private volatile String lastError = "";
    private volatile long lastSuccessfulSaveAt;
    private volatile long lastBackupAt;

    public SqliteNetworkStorageProvider(NetworkStoragePlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "networks.db"));
    }

    public SqliteNetworkStorageProvider(NetworkStoragePlugin plugin, File databaseFile) {
        this.plugin = plugin;
        this.accessRules = plugin.getConfigManager();
        this.databaseFile = databaseFile;
    }

    /** Package-independent constructor used by storage integration tests and tooling. */
    public SqliteNetworkStorageProvider(File databaseFile) {
        this.plugin = null;
        this.accessRules = new NetworkAccessRules() {
            @Override public boolean isGlobalNetworkMode() { return false; }
            @Override public boolean hasPrivilege(org.bukkit.command.CommandSender sender, String permission) { return false; }
            @Override public boolean isTrustSystemEnabled() { return true; }
        };
        this.databaseFile = databaseFile;
    }

    public static void ensureDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new StorageException("sqlite-jdbc driver not found on classpath", e);
        } catch (LinkageError e) {
            throw new StorageException("sqlite-jdbc native driver could not be loaded", e);
        }
    }

    public static DatabaseLayout inspectLayout(File file) {
        if (file == null || !file.exists()) {
            return DatabaseLayout.ABSENT;
        }
        if (file.length() == 0) {
            return DatabaseLayout.EMPTY;
        }
        ensureDriver();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())) {
            int applicationId = queryInt(c, "PRAGMA application_id");
            int userVersion = queryInt(c, "PRAGMA user_version");
            if ((applicationId != 0 && applicationId != Migrations.APPLICATION_ID)
                    || userVersion > Migrations.CURRENT_VERSION) {
                return DatabaseLayout.UNKNOWN;
            }
            boolean hasNetworks = hasTable(c, "networks");
            if (!hasNetworks) {
                return hasAnyUserTable(c) ? DatabaseLayout.UNKNOWN : DatabaseLayout.EMPTY;
            }
            if (hasColumn(c, "networks", "data")) {
                return DatabaseLayout.PACKED_LEGACY;
            }
            if (hasTable(c, "network_chests")
                    && hasTable(c, "network_terminals")
                    && hasTable(c, "network_senders")
                    && hasTable(c, "network_trusted")
                    && hasTable(c, "network_stats")
                    && hasTable(c, "player_state")) {
                return DatabaseLayout.NORMALIZED;
            }
            return DatabaseLayout.UNKNOWN;
        } catch (SQLException e) {
            return DatabaseLayout.UNKNOWN;
        }
    }

    private static boolean hasAnyUserTable(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' LIMIT 1")) {
            return rs.next();
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("No result for " + sql);
            }
            return rs.getInt(1);
        }
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void initialize() {
        shutdown();
        ensureDriver();
        try {
            File parent = databaseFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create SQLite directory " + parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            validateApplicationIdBeforeMigrations();
            applyPragmas();
            applyMigrations();
            validateDatabase();
            available = true;
            lastError = "";
            logger().info("SQLite storage provider initialized at " + databaseFile.getAbsolutePath()
                    + " (schema v" + schemaVersion + ")");
        } catch (LinkageError e) {
            failInitialization(e);
            throw new StorageException("Could not load the SQLite native driver", e);
        } catch (Exception e) {
            failInitialization(e);
            throw new StorageException("Could not initialize SQLite storage provider", e);
        }
    }

    private void failInitialization(Throwable failure) {
        available = false;
        lastError = messageOf(failure);
        closeConnectionQuietly();
        logger().log(Level.SEVERE, "SQLite storage initialization failed; plugin startup will stop", failure);
    }

    @Override
    public void shutdown() {
        writeLock.lock();
        try {
            available = false;
            Connection c = connection;
            connection = null;
            if (c == null) {
                return;
            }
            boolean canCheckpoint = true;
            try {
                if (!c.getAutoCommit()) {
                    try {
                        c.rollback();
                    } catch (SQLException e) {
                        canCheckpoint = false;
                        logger().log(Level.WARNING, "Could not roll back SQLite transaction during shutdown", e);
                    }
                    try {
                        c.setAutoCommit(true);
                    } catch (SQLException e) {
                        canCheckpoint = false;
                        logger().log(Level.WARNING, "Could not restore SQLite auto-commit during shutdown", e);
                    }
                }
                if (canCheckpoint && !c.isClosed()) {
                    try (Statement s = c.createStatement()) {
                        s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    }
                }
            } catch (SQLException e) {
                logger().log(Level.WARNING, "Could not checkpoint SQLite WAL during shutdown", e);
            } finally {
                try {
                    c.close();
                } catch (SQLException e) {
                    logger().log(Level.WARNING, "Could not close SQLite connection cleanly", e);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void closeConnectionQuietly() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public String getBackendName() {
        return "SQLITE";
    }

    @Override
    public int getSchemaVersion() {
        return schemaVersion;
    }

    @Override
    public boolean isEmpty() {
        return countRows("networks") == 0;
    }

    @Override
    public boolean isAvailable() {
        return available && connection != null;
    }

    private void applyPragmas() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            String journal = queryString(s, "PRAGMA journal_mode");
            if (!"wal".equalsIgnoreCase(journal)) {
                throw new SQLException("SQLite WAL mode was not enabled; actual=" + journal);
            }

            s.execute("PRAGMA synchronous = FULL");
            int synchronous = queryInt(s, "PRAGMA synchronous");
            if (synchronous != 2) {
                throw new SQLException("SQLite synchronous=FULL was not enabled; actual=" + synchronous);
            }

            s.execute("PRAGMA foreign_keys = ON");
            if (queryInt(s, "PRAGMA foreign_keys") != 1) {
                throw new SQLException("SQLite foreign_keys=ON was not enabled");
            }

            s.execute("PRAGMA busy_timeout = 5000");
            if (queryInt(s, "PRAGMA busy_timeout") != 5000) {
                throw new SQLException("SQLite busy_timeout was not set to 5000ms");
            }

            s.execute("PRAGMA cache_size = -8000");
            if (queryInt(s, "PRAGMA cache_size") != -8000) {
                throw new SQLException("SQLite cache_size was not set to -8000");
            }
        }
    }

    private void validateApplicationIdBeforeMigrations() throws SQLException {
        try (Statement s = connection.createStatement()) {
            int applicationId = queryInt(s, "PRAGMA application_id");
            if (applicationId != 0 && applicationId != Migrations.APPLICATION_ID) {
                throw new SQLException("Unexpected SQLite application_id " + applicationId);
            }
            if (applicationId == 0 && hasAnyUserTable(connection) && !hasTable(connection, "networks")) {
                throw new SQLException("Existing SQLite database has no recognized application_id");
            }
        }
    }

    private String queryString(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("No result for " + sql);
            }
            return rs.getString(1);
        }
    }

    private int queryInt(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("No result for " + sql);
            }
            return rs.getInt(1);
        }
    }

    private void applyMigrations() throws SQLException {
        int current;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            rs.next();
            current = rs.getInt(1);
        }
        if (current < 0 || current > Migrations.CURRENT_VERSION) {
            throw new SQLException("Unsupported SQLite schema version " + current);
        }

        boolean transactionStarted = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            for (SchemaMigration migration : Migrations.all()) {
                if (migration.targetVersion() <= current) {
                    continue;
                }
                migration.migrate(connection);
                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA user_version = " + migration.targetVersion());
                }
                logger().info("SQLite migration applied: v" + current + " -> v" + migration.targetVersion());
                current = migration.targetVersion();
            }
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA application_id = " + Migrations.APPLICATION_ID);
                if (queryInt(s, "PRAGMA application_id") != Migrations.APPLICATION_ID) {
                    throw new SQLException("Could not set NetworkStorage SQLite application_id");
                }
            }
            connection.commit();
            transactionStarted = false;
        } catch (SQLException | RuntimeException e) {
            if (transactionStarted) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e;
        } finally {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
        }
        schemaVersion = current;

    }

    private void validateDatabase() throws SQLException {
        validateSchemaObjects();
        validateIntegrityChecks();
        String migrationState = getMetadata("migration_state");
        if (migrationState == null) {
            return;
        }
        if (!"COMPLETE".equals(migrationState)) {
            throw new SQLException("SQLite database contains an incomplete migration marker: " + migrationState);
        }
        validateCompletedMigrationMetadata();
    }

    private void validateCompletedMigrationMetadata() throws SQLException {
        String migrationId = getMetadata("migration_id");
        String sourceHash = getMetadata("migration_source_hash");
        String migrationSchema = getMetadata("migration_schema_version");
        String digest = getMetadata("migration_digest");
        String completedAt = getMetadata("migration_completed_at");
        if (migrationId == null || migrationId.isBlank()) {
            throw new SQLException("SQLite migration metadata is missing migration_id");
        }
        if (sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}")) {
            throw new SQLException("SQLite migration metadata has an invalid source hash");
        }
        if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new SQLException("SQLite migration metadata has an invalid digest");
        }
        if (migrationSchema == null || Integer.parseInt(migrationSchema) != schemaVersion) {
            throw new SQLException("SQLite migration metadata has an invalid schema version");
        }
        if (completedAt == null || Long.parseLong(completedAt) <= 0) {
            throw new SQLException("SQLite migration metadata has an invalid completion time");
        }
        for (String countKey : List.of("migration_network_count", "migration_chest_count",
                "migration_terminal_count", "migration_sender_count", "migration_trusted_count",
                "migration_stats_count", "migration_player_state_count")) {
            String value = getMetadata(countKey);
            if (value == null || Long.parseLong(value) < 0) {
                throw new SQLException("SQLite migration metadata has an invalid " + countKey);
            }
        }
    }

    private void validateSchemaObjects() throws SQLException {
        Map<String, List<String>> requiredColumns = Map.of(
                "networks", List.of("name", "owner", "description"),
                "network_chests", List.of("network_name", "world", "x", "y", "z"),
                "network_terminals", List.of("network_name", "world", "x", "y", "z"),
                "network_senders", List.of("network_name", "world", "x", "y", "z"),
                "network_trusted", List.of("network_name", "player_uuid", "expires_at"),
                "network_stats", List.of("network_name", "player_uuid", "player_name", "deposited", "withdrawn"),
                "player_state", List.of("player_uuid", "selected_network", "selected_wireless"),
                "storage_metadata", List.of("key", "value"));
        for (Map.Entry<String, List<String>> table : requiredColumns.entrySet()) {
            if (!hasTable(connection, table.getKey())) {
                throw new SQLException("SQLite schema is missing table '" + table.getKey() + "'");
            }
            for (String column : table.getValue()) {
                if (!hasColumn(connection, table.getKey(), column)) {
                    throw new SQLException("SQLite schema table '" + table.getKey()
                            + "' is missing column '" + column + "'");
                }
            }
        }
    }

    private void validateIntegrityChecks() throws SQLException {
        boolean quickCheckReturnedRow = false;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
            while (rs.next()) {
                quickCheckReturnedRow = true;
                if (!"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new SQLException("SQLite quick_check failed: " + rs.getString(1));
                }
            }
        }
        if (!quickCheckReturnedRow) {
            throw new SQLException("SQLite quick_check returned no result");
        }
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new SQLException("SQLite foreign_key_check found a violation");
            }
        }
    }

    @Override
    public void loadNetworks(Map<String, Network> networks,
                             Map<UUID, String> selectedNetworks,
                             Map<UUID, String> selectedWirelessNetworks) {
        requireAvailable();
        boolean transactionStarted = false;
        try (Statement s = connection.createStatement()) {
            s.execute("BEGIN");
            transactionStarted = true;
            try (PreparedStatement purge = connection.prepareStatement(
                    "DELETE FROM network_trusted WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                purge.setLong(1, System.currentTimeMillis());
                purge.executeUpdate();
            }
            try (ResultSet rs = s.executeQuery("SELECT name, owner, description FROM networks ORDER BY name")) {
                Set<String> canonicalNames = new HashSet<>();
                while (rs.next()) {
                    String name = rs.getString("name");
                    String ownerValue = rs.getString("owner");
                    if (!StorageValues.isValidNetworkName(name)) {
                        throw new SQLException("Stored network has an invalid name '" + name + "'");
                    }
                    if (!canonicalNames.add(StorageValues.canonicalNetworkName(name))) {
                        throw new SQLException("Stored networks contain a case-insensitive name collision at '" + name + "'");
                    }
                    if (ownerValue == null || ownerValue.isBlank()) {
                        throw new SQLException("Stored network '" + name + "' has no owner UUID");
                    }
                    UUID owner;
                    try {
                        owner = UUID.fromString(ownerValue);
                    } catch (IllegalArgumentException e) {
                        throw new SQLException("Stored network '" + name + "' has an invalid owner UUID", e);
                    }
                    Network network = new Network(name, owner, accessRules,
                            plugin == null ? MovementEvents.NOOP : plugin.getMovementEvents());
                    String description = rs.getString("description");
                    if (description == null) {
                        throw new SQLException("Stored network '" + name + "' has no description value");
                    }
                    if (description.length() > Network.MAX_DESCRIPTION_LENGTH) {
                        throw new SQLException("Stored network '" + name + "' has an overlong description");
                    }
                    if (description != null && !description.isEmpty()) {
                        network.setDescription(description);
                    }
                    loadLocationsFor("network_chests", name).forEach(location -> addStoredLocation(
                            network, location, network::addChest, network::addUnloadedChest));
                    loadLocationsFor("network_terminals", name).forEach(location -> addStoredLocation(
                            network, location, network::addTerminal, network::addUnloadedTerminal));
                    loadLocationsFor("network_senders", name).forEach(location -> addStoredLocation(
                            network, location, network::addSenderChest, network::addUnloadedSenderChest));
                    loadTrustsFor(name, network);
                    loadStatsFor(name, network);
                    network.setDirty(false);
                    networks.put(name, network);
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT player_uuid, selected_network, selected_wireless FROM player_state")) {
                while (rs.next()) {
                    UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                    String selected = rs.getString("selected_network");
                    String wireless = rs.getString("selected_wireless");
                    if (selected != null && !selected.isBlank()) {
                        selectedNetworks.put(playerId, selected);
                    }
                    if (wireless != null && !wireless.isBlank()) {
                        selectedWirelessNetworks.put(playerId, wireless);
                    }
                }
            }
            s.execute("COMMIT");
            transactionStarted = false;
        } catch (Exception e) {
            if (transactionStarted) {
                try (Statement rollback = connection.createStatement()) {
                    rollback.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw new StorageException("Could not load networks from SQLite", e);
        }
    }

    private List<StoredLocation> loadLocationsFor(String table, String networkName) throws SQLException {
        List<StoredLocation> locations = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world, x, y, z FROM " + table + " WHERE network_name = ? ORDER BY world, x, y, z")) {
            ps.setString(1, networkName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    locations.add(readStoredLocation(rs.getString("world"),
                            rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                }
            }
        }
        return locations;
    }

    private StoredLocation readStoredLocation(String worldName, int x, int y, int z) throws SQLException {
        if (worldName == null || worldName.isBlank()) {
            throw new SQLException("Stored location has no world");
        }
        if (Math.abs((long) x) > 30_000_000L
                || Math.abs((long) z) > 30_000_000L
                || Math.abs((long) y) > 30_000_000L) {
            throw new SQLException("Stored location is outside safe world bounds");
        }
        return new StoredLocation(worldName, x, y, z);
    }

    private void addStoredLocation(Network network,
                                   StoredLocation stored,
                                   java.util.function.Consumer<Location> addResolved,
                                   java.util.function.Consumer<StoredLocation> addUnresolved) {
        World world = plugin == null ? null : Bukkit.getWorld(stored.world());
        if (world == null) {
            addUnresolved.accept(stored);
            return;
        }
        addResolved.accept(stored.resolve(world));
    }

    private void loadTrustsFor(String networkName, Network network) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, expires_at FROM network_trusted WHERE network_name = ? ORDER BY player_uuid")) {
            ps.setString(1, networkName);
            try (ResultSet rs = ps.executeQuery()) {
                long now = System.currentTimeMillis();
                while (rs.next()) {
                    UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                    Object rawExpiry = rs.getObject("expires_at");
                    if (rawExpiry == null) {
                        network.addTrustedPlayer(playerId);
                    } else if (!(rawExpiry instanceof Number number)) {
                        throw new SQLException("Stored trust expiry is not numeric");
                    } else {
                        long expiry = readIntegralLong(number, "Stored trust expiry");
                        if (expiry > now) {
                            network.addTrustedPlayerWithExpiry(playerId, expiry);
                        }
                    }
                }
            }
        }
    }

    private void loadStatsFor(String networkName, Network network) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, deposited, withdrawn FROM network_stats WHERE network_name = ? ORDER BY player_uuid")) {
            ps.setString(1, networkName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                    String playerName = rs.getString("player_name");
                    if (playerName == null || playerName.isBlank()
                            || (!playerName.equals("Unknown Player")
                            && !playerName.matches("^[A-Za-z0-9_]{3,16}$"))) {
                        throw new SQLException("Stored player stat has an invalid player name");
                    }
                    long deposited = readIntegralLong(rs.getObject("deposited"), "Stored deposited counter");
                    long withdrawn = readIntegralLong(rs.getObject("withdrawn"), "Stored withdrawn counter");
                    if (deposited < 0 || withdrawn < 0) {
                        throw new SQLException("Stored player stat contains a negative counter");
                    }
                    network.getPlayerStats().put(playerId,
                            new PlayerStat(playerId, playerName, deposited, withdrawn));
                }
            }
        }
    }

    @Override
    public boolean saveSnapshot(Collection<Network> networks,
                                Map<UUID, String> selectedNetworks,
                                Map<UUID, String> selectedWirelessNetworks) {
        if (!isAvailable()) {
            lastError = "SQLite provider is not available";
            return false;
        }
        StorageSnapshot snapshot;
        try {
            snapshot = StorageSnapshot.capture(networks, selectedNetworks, selectedWirelessNetworks);
        } catch (RuntimeException e) {
            lastError = messageOf(e);
            logger().log(Level.SEVERE, "Could not capture SQLite snapshot; changes remain dirty", e);
            return false;
        }
        return saveSnapshot(snapshot);
    }

    /** Accepts an already detached snapshot; safe to invoke from the persistence worker. */
    public boolean saveSnapshot(StorageSnapshot snapshot) {
        if (!isAvailable()) {
            lastError = "SQLite provider is not available";
            return false;
        }
        writeLock.lock();
        boolean transactionStarted = false;
        Connection activeConnection = connection;
        if (activeConnection == null || !available) {
            writeLock.unlock();
            lastError = "SQLite provider became unavailable before save";
            return false;
        }
        try (Statement tx = activeConnection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            transactionStarted = true;
            clearSnapshotTables(activeConnection);
            insertSnapshot(activeConnection, snapshot);
            tx.execute("COMMIT");
            transactionStarted = false;
            lastSuccessfulSaveAt = System.currentTimeMillis();
            lastError = "";
            return true;
        } catch (SQLException | RuntimeException e) {
            if (transactionStarted) {
                rollbackQuietly(activeConnection, e);
            }
            lastError = messageOf(e);
            logger().log(Level.SEVERE, "SQLite snapshot save failed; changes remain dirty", e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    private void clearSnapshotTables(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM network_chests");
            s.executeUpdate("DELETE FROM network_terminals");
            s.executeUpdate("DELETE FROM network_senders");
            s.executeUpdate("DELETE FROM network_trusted");
            s.executeUpdate("DELETE FROM network_stats");
            s.executeUpdate("DELETE FROM networks");
            s.executeUpdate("DELETE FROM player_state");
        }
    }

    private void insertSnapshot(Connection c, StorageSnapshot snapshot) throws SQLException {
        Set<String> networkNames = new HashSet<>();
        Set<String> canonicalNetworkNames = new HashSet<>();
        for (StorageSnapshot.NetworkData network : snapshot.networks()) {
            if (network == null || network.name() == null || network.name().isBlank()) {
                throw new SQLException("Snapshot contains a network with no valid name");
            }
            if (!networkNames.add(network.name())) {
                throw new SQLException("Snapshot contains duplicate network '" + network.name() + "'");
            }
            if (!canonicalNetworkNames.add(StorageValues.canonicalNetworkName(network.name()))) {
                throw new SQLException("Snapshot contains a case-insensitive network-name collision at '"
                        + network.name() + "'");
            }
        }
        try (PreparedStatement networksInsert = c.prepareStatement(
                "INSERT INTO networks(name, owner, description) VALUES(?, ?, ?)");
             PreparedStatement chestInsert = c.prepareStatement(
                     "INSERT INTO network_chests(network_name, world, x, y, z) VALUES(?, ?, ?, ?, ?)");
             PreparedStatement terminalInsert = c.prepareStatement(
                     "INSERT INTO network_terminals(network_name, world, x, y, z) VALUES(?, ?, ?, ?, ?)");
             PreparedStatement senderInsert = c.prepareStatement(
                     "INSERT INTO network_senders(network_name, world, x, y, z) VALUES(?, ?, ?, ?, ?)");
             PreparedStatement trustInsert = c.prepareStatement(
                     "INSERT INTO network_trusted(network_name, player_uuid, expires_at) VALUES(?, ?, ?)");
             PreparedStatement statsInsert = c.prepareStatement(
                     "INSERT INTO network_stats(network_name, player_uuid, player_name, deposited, withdrawn) VALUES(?, ?, ?, ?, ?)");
             PreparedStatement stateInsert = c.prepareStatement(
                     "INSERT INTO player_state(player_uuid, selected_network, selected_wireless) VALUES(?, ?, ?)")) {

            for (StorageSnapshot.NetworkData network : snapshot.networks()) {
                if (!StorageValues.isValidNetworkName(network.name())
                        || network.owner() == null) {
                    throw new SQLException("Network has no valid name or owner");
                }
                networksInsert.setString(1, network.name());
                networksInsert.setString(2, network.owner().toString());
                networksInsert.setString(3, network.description());
                networksInsert.executeUpdate();

                insertLocations(chestInsert, network.name(), network.chests());
                insertLocations(terminalInsert, network.name(), network.terminals());
                insertLocations(senderInsert, network.name(), network.senders());

                for (StorageSnapshot.TrustedPlayer trusted : network.trusted()) {
                    UUID playerId = trusted.playerId();
                    if (playerId == null) {
                        throw new SQLException("Network '" + network.name() + "' contains a null trusted UUID");
                    }
                    trustInsert.setString(1, network.name());
                    trustInsert.setString(2, playerId.toString());
                    Long expiry = trusted.expiresAt();
                    if (expiry == null) {
                        trustInsert.setNull(3, java.sql.Types.INTEGER);
                    } else {
                        trustInsert.setLong(3, expiry);
                    }
                    trustInsert.addBatch();
                }

                for (StorageSnapshot.PlayerStatData stat : network.stats()) {
                    if (stat == null || stat.playerId() == null) {
                        throw new SQLException("Network '" + network.name() + "' contains an invalid player stat");
                    }
                    statsInsert.setString(1, network.name());
                    statsInsert.setString(2, stat.playerId().toString());
                    statsInsert.setString(3, stat.playerName());
                    if (stat.deposited() < 0 || stat.withdrawn() < 0) {
                        throw new SQLException("Network '" + network.name() + "' contains negative player counters");
                    }
                    statsInsert.setLong(4, stat.deposited());
                    statsInsert.setLong(5, stat.withdrawn());
                    statsInsert.addBatch();
                }
            }

            Set<UUID> playerIds = new HashSet<>(snapshot.selectedNetworks().keySet());
            playerIds.addAll(snapshot.selectedWirelessNetworks().keySet());
            for (UUID playerId : playerIds) {
                if (playerId == null) {
                    throw new SQLException("Player state contains a null UUID");
                }
                String selectedNetwork = snapshot.selectedNetworks().get(playerId);
                String selectedWireless = snapshot.selectedWirelessNetworks().get(playerId);
                if (selectedNetwork != null && !networkNames.contains(selectedNetwork)) {
                    throw new SQLException("Player state references missing network '" + selectedNetwork + "'");
                }
                if (selectedWireless != null && !networkNames.contains(selectedWireless)) {
                    throw new SQLException("Player state references missing wireless network '" + selectedWireless + "'");
                }
                stateInsert.setString(1, playerId.toString());
                stateInsert.setString(2, selectedNetwork);
                stateInsert.setString(3, selectedWireless);
                stateInsert.addBatch();
            }
            // Keep the transaction durable but avoid one JNI/SQLite round trip per child row.
            chestInsert.executeBatch();
            terminalInsert.executeBatch();
            senderInsert.executeBatch();
            trustInsert.executeBatch();
            statsInsert.executeBatch();
            stateInsert.executeBatch();
        }
    }

    private void insertLocations(PreparedStatement insert,
                                 String networkName,
                                 Collection<StorageSnapshot.LocationData> locations) throws SQLException {
        for (StorageSnapshot.LocationData location : locations) {
            if (location == null || location.world() == null || location.world().isBlank()) {
                throw new SQLException("Network '" + networkName + "' contains an invalid location");
            }
            if (Math.abs(location.x()) > 30_000_000L || Math.abs(location.z()) > 30_000_000L) {
                throw new SQLException("Network '" + networkName + "' contains an out-of-bounds location");
            }
            insert.setString(1, networkName);
            insert.setString(2, location.world());
            insert.setInt(3, location.x());
            insert.setInt(4, location.y());
            insert.setInt(5, location.z());
            insert.addBatch();
        }
    }

    public boolean setMigrationMetadata(String migrationId,
                                         String sourceHash,
                                         StorageSnapshot snapshot) {
        if (!isAvailable()) {
            return false;
        }
        writeLock.lock();
        boolean transactionStarted = false;
        Connection activeConnection = connection;
        if (activeConnection == null || !available) {
            writeLock.unlock();
            lastError = "SQLite provider became unavailable before migration metadata write";
            return false;
        }
        try (Statement tx = activeConnection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            transactionStarted = true;
            putMetadata(activeConnection, "migration_id", migrationId);
            putMetadata(activeConnection, "migration_source_hash", sourceHash);
            putMetadata(activeConnection, "migration_schema_version", String.valueOf(schemaVersion));
            putMetadata(activeConnection, "migration_network_count", String.valueOf(snapshot.networkCount()));
            putMetadata(activeConnection, "migration_chest_count", String.valueOf(snapshot.chestCount()));
            putMetadata(activeConnection, "migration_terminal_count", String.valueOf(snapshot.terminalCount()));
            putMetadata(activeConnection, "migration_sender_count", String.valueOf(snapshot.senderCount()));
            putMetadata(activeConnection, "migration_trusted_count", String.valueOf(snapshot.trustedCount()));
            putMetadata(activeConnection, "migration_stats_count", String.valueOf(snapshot.statsCount()));
            putMetadata(activeConnection, "migration_player_state_count", String.valueOf(snapshot.playerStateCount()));
            putMetadata(activeConnection, "migration_digest", snapshot.digest());
            putMetadata(activeConnection, "migration_completed_at", String.valueOf(System.currentTimeMillis()));
            putMetadata(activeConnection, "migration_state", "COMPLETE");
            tx.execute("COMMIT");
            transactionStarted = false;
            return true;
        } catch (SQLException | RuntimeException e) {
            if (transactionStarted) {
                rollbackQuietly(activeConnection, e);
            }
            lastError = messageOf(e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean setMigrationInProgress(String migrationId, String sourceHash) {
        if (!isAvailable()) {
            return false;
        }
        writeLock.lock();
        boolean transactionStarted = false;
        Connection activeConnection = connection;
        if (activeConnection == null || !available) {
            writeLock.unlock();
            lastError = "SQLite provider became unavailable before migration marker write";
            return false;
        }
        try (Statement tx = activeConnection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            transactionStarted = true;
            putMetadata(activeConnection, "migration_id", migrationId);
            putMetadata(activeConnection, "migration_source_hash", sourceHash);
            putMetadata(activeConnection, "migration_schema_version", String.valueOf(schemaVersion));
            putMetadata(activeConnection, "migration_state", "IN_PROGRESS");
            tx.execute("COMMIT");
            transactionStarted = false;
            return true;
        } catch (SQLException | RuntimeException e) {
            if (transactionStarted) {
                rollbackQuietly(activeConnection, e);
            }
            lastError = messageOf(e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    public void verifySnapshot(StorageSnapshot expected) {
        requireAvailable();
        try {
            validateIntegrityChecks();
        } catch (SQLException e) {
            throw new StorageException("SQLite integrity verification failed", e);
        }
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("networks", countRows("networks"));
        actual.put("chests", countRows("network_chests"));
        actual.put("terminals", countRows("network_terminals"));
        actual.put("senders", countRows("network_senders"));
        actual.put("trusted", countRows("network_trusted"));
        actual.put("stats", countRows("network_stats"));
        actual.put("player_state", countRows("player_state"));
        Map<String, Long> expectedCounts = Map.of(
                "networks", expected.networkCount(),
                "chests", expected.chestCount(),
                "terminals", expected.terminalCount(),
                "senders", expected.senderCount(),
                "trusted", expected.trustedCount(),
                "stats", expected.statsCount(),
                "player_state", expected.playerStateCount());
        if (!expectedCounts.equals(actual)) {
            throw new StorageException("SQLite migration row-count verification failed: expected "
                    + expectedCounts + ", actual " + actual);
        }
        if (!expected.digest().equals(canonicalDigest())) {
            throw new StorageException("SQLite migration digest verification failed");
        }
    }

    public void verifyDatabase() {
        requireAvailable();
        try {
            validateDatabase();
        } catch (SQLException e) {
            throw new StorageException("SQLite database verification failed", e);
        }
    }

    private void putMetadata(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO storage_metadata(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public String getMetadata(String key) {
        if (connection == null) {
            throw new StorageException("SQLite provider is not connected");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM storage_metadata WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StorageException("Could not read SQLite migration metadata", e);
        }
    }

    public String canonicalDigest() {
        requireAvailable();
        List<String> rows = new ArrayList<>();
        try (Statement s = connection.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT name, owner, description FROM networks")) {
                while (rs.next()) {
                    rows.add("network|" + rs.getString("name") + "|" + rs.getString("owner") + "|"
                            + nullToEmpty(rs.getString("description")));
                }
            }
            addLocationRows(rows, s, "network_chests", "chest");
            addLocationRows(rows, s, "network_terminals", "terminal");
            addLocationRows(rows, s, "network_senders", "sender");
            try (ResultSet rs = s.executeQuery("SELECT network_name, player_uuid, expires_at FROM network_trusted")) {
                while (rs.next()) {
                    long expiry = rs.getLong("expires_at");
                    rows.add("trusted|" + rs.getString("network_name") + "|" + rs.getString("player_uuid") + "|"
                            + (rs.wasNull() ? "" : expiry));
                }
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT network_name, player_uuid, player_name, deposited, withdrawn FROM network_stats")) {
                while (rs.next()) {
                    rows.add("stat|" + rs.getString("network_name") + "|" + rs.getString("player_uuid") + "|"
                            + rs.getString("player_name") + "|" + rs.getLong("deposited") + "|"
                            + rs.getLong("withdrawn"));
                }
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT player_uuid, selected_network, selected_wireless FROM player_state")) {
                while (rs.next()) {
                    rows.add("player|" + rs.getString("player_uuid") + "|"
                            + nullToEmpty(rs.getString("selected_network")) + "|"
                            + nullToEmpty(rs.getString("selected_wireless")));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Could not calculate SQLite digest", e);
        }
        rows.sort(Comparator.naturalOrder());
        return digestRows(rows);
    }

    private void addLocationRows(List<String> rows,
                                 Statement s,
                                 String table,
                                 String type) throws SQLException {
        try (ResultSet rs = s.executeQuery(
                "SELECT network_name, world, x, y, z FROM " + table)) {
            while (rs.next()) {
                rows.add(type + "|" + rs.getString("network_name") + "|" + rs.getString("world") + "|"
                        + rs.getInt("x") + "|" + rs.getInt("y") + "|" + rs.getInt("z"));
            }
        }
    }

    private String digestRows(List<String> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String row : rows) {
                digest.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 is unavailable", e);
        }
    }

    public boolean backupTo(File destination) {
        if (!isAvailable() || destination == null) {
            return false;
        }
        writeLock.lock();
        File absoluteDestination = destination.getAbsoluteFile();
        File parent = absoluteDestination.getParentFile();
        File temp = new File(parent, absoluteDestination.getName() + ".tmp-" + UUID.randomUUID());
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create backup directory " + parent);
            }
            String escaped = temp.getAbsolutePath().replace("'", "''");
            try (Statement s = connection.createStatement()) {
                s.execute("BACKUP TO '" + escaped + "'");
            }
            verifyBackupContents(temp);
            Files.move(temp.toPath(), absoluteDestination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            lastBackupAt = System.currentTimeMillis();
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = messageOf(e);
            logger().log(Level.WARNING, "Could not create SQLite backup", e);
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignored) {
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean verifyBackup(File backup) {
        if (backup == null || !backup.isFile()) {
            return false;
        }
        writeLock.lock();
        try {
            verifyBackupContents(backup);
            return true;
        } catch (SQLException e) {
            lastError = messageOf(e);
            logger().log(Level.WARNING, "SQLite backup integrity verification failed for " + backup, e);
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    private void verifyBackupContents(File backup) throws SQLException {
        try (Connection backupConnection = DriverManager.getConnection("jdbc:sqlite:" + backup.getAbsolutePath())) {
            boolean quickCheckReturnedRow = false;
            try (Statement s = backupConnection.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
                while (rs.next()) {
                    quickCheckReturnedRow = true;
                    if (!"ok".equalsIgnoreCase(rs.getString(1))) {
                        throw new SQLException("Backup quick_check failed");
                    }
                }
            }
            if (!quickCheckReturnedRow) {
                throw new SQLException("Backup quick_check returned no result");
            }
            try (Statement s = backupConnection.createStatement();
                 ResultSet foreignKeys = s.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) {
                    throw new SQLException("Backup foreign_key_check failed");
                }
            }
            int applicationId = queryInt(backupConnection, "PRAGMA application_id");
            if (applicationId != Migrations.APPLICATION_ID) {
                throw new SQLException("Backup application_id was not NetworkStorage's application id");
            }
            int userVersion = queryInt(backupConnection, "PRAGMA user_version");
            if (userVersion != Migrations.CURRENT_VERSION) {
                throw new SQLException("Backup schema version was not current: " + userVersion);
            }
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new StorageException("SQLite provider is unavailable: " + lastError);
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("backend", "SQLITE");
        map.put("available", isAvailable());
        map.put("health", isAvailable() ? "READY" : "FAILED");
        map.put("path", databaseFile.getAbsolutePath());
        map.put("size_bytes", databaseFile.exists() ? databaseFile.length() : 0);
        map.put("wal_size_bytes", fileSize(new File(databaseFile.getAbsolutePath() + "-wal")));
        map.put("shm_size_bytes", fileSize(new File(databaseFile.getAbsolutePath() + "-shm")));
        map.put("schema_version", schemaVersion);
        map.put("application_id", querySnapshotValue("application_id"));
        map.put("journal_mode", querySnapshotValue("journal_mode"));
        map.put("synchronous", querySnapshotValue("synchronous"));
        map.put("foreign_keys", querySnapshotValue("foreign_keys"));
        map.put("busy_timeout", querySnapshotValue("busy_timeout"));
        map.put("migration_state", getMetadata("migration_state"));
        map.put("migration_id", getMetadata("migration_id"));
        map.put("migration_source_hash", getMetadata("migration_source_hash"));
        map.put("migration_completed_at", getMetadata("migration_completed_at"));
        map.put("migration_digest", getMetadata("migration_digest"));
        map.put("last_successful_save", lastSuccessfulSaveAt);
        map.put("last_backup", lastBackupAt > 0 ? lastBackupAt : latestBackupAt());
        map.put("last_error", lastError);
        map.put("networks", countRows("networks"));
        map.put("chests", countRows("network_chests"));
        map.put("terminals", countRows("network_terminals"));
        map.put("senders", countRows("network_senders"));
        map.put("trusted_entries", countRows("network_trusted"));
        map.put("stats_rows", countRows("network_stats"));
        map.put("player_state_rows", countRows("player_state"));
        return map;
    }

    private long fileSize(File file) {
        return file.exists() ? file.length() : 0L;
    }

    private long latestBackupAt() {
        File parent = databaseFile.getAbsoluteFile().getParentFile();
        if (parent == null) {
            return 0L;
        }
        File[] backups = parent.listFiles((directory, name) ->
                name.startsWith(databaseFile.getName() + ".backup-") && name.endsWith(".db"));
        if (backups == null) {
            return 0L;
        }
        long latest = 0L;
        for (File backup : backups) {
            latest = Math.max(latest, backup.lastModified());
        }
        return latest;
    }

    private long countRows(String table) {
        if (!isAvailable()) {
            return 0L;
        }
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    private String querySnapshotValue(String pragma) {
        if (!isAvailable()) {
            return "unavailable";
        }
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA " + pragma)) {
            return rs.next() ? rs.getString(1) : "unknown";
        } catch (SQLException e) {
            return "error";
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void rollbackQuietly(Connection activeConnection, Throwable failure) {
        try {
            if (activeConnection != null && !activeConnection.isClosed()) {
                activeConnection.rollback();
            }
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private long readIntegralLong(Object rawValue, String field) throws SQLException {
        try {
            return StorageValues.exactLong(rawValue, field);
        } catch (StorageException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    private Logger logger() {
        return plugin == null ? Logger.getLogger(SqliteNetworkStorageProvider.class.getName()) : plugin.getLogger();
    }
}
