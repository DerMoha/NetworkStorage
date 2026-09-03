package com.dermoha.networkstorage.storage.sqlite;

import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkAccessRules;
import com.dermoha.networkstorage.storage.StorageException;
import com.dermoha.networkstorage.storage.StorageSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProviderIntegrationTest {

    private Path tempDirectory;
    private Path database;

    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = Files.createTempDirectory("networkstorage-provider-");
        database = tempDirectory.resolve("networks.db");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDirectory != null) {
            try (var paths = Files.walk(tempDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void initializesAndValidatesProductionPragmas() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            Map<String, Object> health = provider.snapshot();
            assertEquals("wal", String.valueOf(health.get("journal_mode")).toLowerCase());
            assertEquals("2", String.valueOf(health.get("synchronous")));
            assertEquals("1", String.valueOf(health.get("foreign_keys")));
            assertEquals("5000", String.valueOf(health.get("busy_timeout")));
            assertEquals(Migrations.APPLICATION_ID, Integer.parseInt(pragma(statement, "application_id")));
            assertEquals(Migrations.CURRENT_VERSION, Integer.parseInt(pragma(statement, "user_version")));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void snapshotCommitReconcilesDeletesAndRollsBackInvalidWrites() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        Network network = network("Main", owner);
        network.setDescription("kept");
        network.addTrustedPlayer(trusted);
        network.getPlayerStats().put(owner, new PlayerStat(owner, "Alex", 4, 2));
        Map<UUID, String> selected = new HashMap<>(Map.of(owner, "Main"));
        Map<UUID, String> wireless = new HashMap<>(Map.of(trusted, "Main"));
        try {
            assertTrue(provider.saveSnapshot(java.util.List.of(network), selected, wireless, Map.of()));
            assertEquals(1L, count(provider, "networks"));
            assertEquals(1L, count(provider, "network_trusted"));
            assertEquals(2L, count(provider, "player_state"));

            assertTrue(provider.saveSnapshot(java.util.List.of(), Map.of(), Map.of(), Map.of()));
            assertEquals(0L, count(provider, "networks"));
            assertEquals(0L, count(provider, "network_trusted"));
            assertEquals(0L, count(provider, "network_stats"));
            assertEquals(0L, count(provider, "player_state"));

            Network invalid = network("Invalid", owner);
            invalid.addChest(null);
            assertFalse(provider.saveSnapshot(java.util.List.of(invalid), Map.of(), Map.of(), Map.of()));
            assertEquals(0L, count(provider, "networks"), "failed snapshot must not partially replace the live snapshot");

            Network upper = network("Main", owner);
            Network lower = network("main", UUID.randomUUID());
            assertFalse(provider.saveSnapshot(java.util.List.of(upper, lower), Map.of(), Map.of(), Map.of()));
            assertEquals(0L, count(provider, "networks"), "case-insensitive collisions must roll back");
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void backupUsesASeparateVerifiedDatabase() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        Path backup = tempDirectory.resolve("networks.db.backup.db");
        try {
            assertTrue(provider.saveSnapshot(java.util.List.of(), Map.of(), Map.of(), Map.of()));
            assertTrue(provider.backupTo(backup.toFile()));
            assertTrue(provider.verifyBackup(backup.toFile()));
            assertTrue(Files.size(backup) > 0);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void backupManagerRetainsOnlyVerifiedNewestCopies() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        SqliteBackupManager backups = new SqliteBackupManager(provider, tempDirectory.toFile(), null);
        try {
            for (int i = 0; i < 4; i++) {
                assertTrue(provider.saveSnapshot(java.util.List.of(), Map.of(), Map.of(), Map.of()));
                assertTrue(backups.createBackup());
                Thread.sleep(2L);
            }
        } finally {
            provider.shutdown();
        }
        try (var files = Files.list(tempDirectory)) {
            long backupCount = files.filter(path -> path.getFileName().toString().startsWith("networks.db.backup-"))
                    .count();
            assertEquals(3L, backupCount);
        }
    }

    @Test
    void providerSerializesConcurrentSnapshotsAndBackups() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var saves = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    if (!provider.saveSnapshot(java.util.List.of(), Map.of(), Map.of(), Map.of())) {
                        throw new AssertionError("snapshot save failed");
                    }
                }
            });
            var backups = pool.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    if (!provider.backupTo(tempDirectory.resolve("concurrent-" + i + ".db").toFile())) {
                        throw new AssertionError("backup failed");
                    }
                }
            });
            saves.get(30, TimeUnit.SECONDS);
            backups.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            provider.shutdown();
        }
    }

    @Test
    void rejectsFutureSchemaInvalidApplicationIdAndCorruptFiles() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 999");
        }
        assertThrows(StorageException.class, () -> new SqliteNetworkStorageProvider(database.toFile()).initialize());

        Files.delete(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA application_id = 1234");
        }
        assertThrows(StorageException.class, () -> new SqliteNetworkStorageProvider(database.toFile()).initialize());

        Files.delete(database);
        Files.writeString(database, "not a sqlite database", StandardCharsets.UTF_8);
        assertThrows(StorageException.class, () -> new SqliteNetworkStorageProvider(database.toFile()).initialize());
    }

    @Test
    void repairsRecognizedSchemaThatWasLeftWithoutAnApplicationId() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        provider.shutdown();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA application_id = 0");
        }

        provider.initialize();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            assertEquals(String.valueOf(Migrations.APPLICATION_ID), pragma(statement, "application_id"));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void detectsPackedLegacySchema() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE networks(name TEXT PRIMARY KEY, owner TEXT NOT NULL, description TEXT, data TEXT NOT NULL)");
        }
        assertEquals(SqliteNetworkStorageProvider.DatabaseLayout.PACKED_LEGACY,
                SqliteNetworkStorageProvider.inspectLayout(database.toFile()));
    }

    @Test
    void loadsAndPreservesLocationsWhoseWorldIsUnavailable() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        UUID owner = UUID.randomUUID();
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO networks(name, owner, description) VALUES('Remote', '"
                        + owner + "', '')");
                statement.execute("INSERT INTO network_chests(network_name, world, x, y, z) "
                        + "VALUES('Remote', 'season_two', 12, 64, -8)");
            }

            Map<String, Network> networks = new HashMap<>();
            provider.loadNetworks(networks, new HashMap<>(), new HashMap<>(), new HashMap<>());

            Network loaded = networks.get("Remote");
            assertTrue(loaded.getChestLocations().isEmpty());
            assertEquals(1, loaded.getUnloadedChestLocations().size());
            assertEquals("season_two", loaded.getUnloadedChestLocations().iterator().next().world());
            assertTrue(provider.saveSnapshot(networks.values(), Map.of(), Map.of(), Map.of()));
            assertEquals(1L, count(provider, "network_chests"));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void rejectsStoredCaseInsensitiveNetworkNameCollisions() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO networks(name, owner, description) VALUES('Main', '"
                        + UUID.randomUUID() + "', '')");
                statement.execute("INSERT INTO networks(name, owner, description) VALUES('main', '"
                        + UUID.randomUUID() + "', '')");
            }

            assertThrows(StorageException.class,
                    () -> provider.loadNetworks(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void persistsPlayerSortTypesAndClearsThemBackToNull() throws Exception {
        SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
        provider.initialize();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Network network = network("Main", owner);
        Map<UUID, String> selected = new HashMap<>(Map.of(owner, "Main"));
        Map<UUID, String> sortTypes = new HashMap<>(Map.of(owner, "COUNT_DESC", other, "COUNT_ASC"));
        try {
            assertTrue(provider.saveSnapshot(java.util.List.of(network), selected, Map.of(), sortTypes));

            Map<String, Network> networks = new HashMap<>();
            Map<UUID, String> loadedSelected = new HashMap<>();
            Map<UUID, String> loadedWireless = new HashMap<>();
            Map<UUID, String> loadedSortTypes = new HashMap<>();
            provider.loadNetworks(networks, loadedSelected, loadedWireless, loadedSortTypes);
            assertEquals("COUNT_DESC", loadedSortTypes.get(owner));
            assertEquals("COUNT_ASC", loadedSortTypes.get(other));
            assertTrue(loadedSelected.containsKey(owner));
            assertTrue(loadedWireless.isEmpty());

            StorageSnapshot captured = StorageSnapshot.capture(networks.values(), loadedSelected, loadedWireless, loadedSortTypes);
            provider.verifySnapshot(captured);

            assertTrue(provider.saveSnapshot(java.util.List.of(network), selected, Map.of(), Map.of()));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT sort_type FROM player_state WHERE player_uuid = '" + owner + "'")) {
                assertTrue(rs.next(), "expected player_state row to survive the save");
                assertTrue(rs.getString("sort_type") == null, "expected sort_type to be NULL after clearing");
            }
        } finally {
            provider.shutdown();
        }
    }

    private Network network(String name, UUID owner) {
        NetworkAccessRules rules = new NetworkAccessRules() {
            @Override public boolean isGlobalNetworkMode() { return false; }
            @Override public boolean hasPrivilege(org.bukkit.command.CommandSender sender, String permission) { return false; }
            @Override public boolean isTrustSystemEnabled() { return true; }
        };
        return new Network(name, owner, rules);
    }

    private long count(SqliteNetworkStorageProvider provider, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String pragma(Statement statement, String name) throws Exception {
        try (var result = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
