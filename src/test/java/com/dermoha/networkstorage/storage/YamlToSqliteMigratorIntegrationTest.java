package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.storage.sqlite.SqliteNetworkStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlToSqliteMigratorIntegrationTest {

    private Path dataDirectory;
    private Path networksYaml;
    private Path playerStateYaml;
    private Path database;
    private final UUID owner = UUID.randomUUID();
    private final UUID trusted = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        dataDirectory = Files.createTempDirectory("networkstorage-migration-");
        networksYaml = dataDirectory.resolve("networks.yml");
        playerStateYaml = dataDirectory.resolve("player-state.yml");
        database = dataDirectory.resolve("networks.db");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataDirectory != null) {
            try (var paths = Files.walk(dataDirectory)) {
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
    void yamlImportBacksUpArchivesAndRecordsVerifiedMetadata() throws Exception {
        writeYaml("""
                networks:
                  Main:
                    owner: %s
                    description: migrated network
                    trusted:
                      - %s
                    trusted-expiry:
                      %s: %d
                    stats:
                      %s:
                        name: Alex
                        deposited: 4
                        withdrawn: 2
                """.formatted(owner, trusted, trusted, System.currentTimeMillis() + 60_000, owner));
        Files.writeString(playerStateYaml, """
                players:
                  %s:
                    selected-owned-network: Main
                    selected-wireless-network: Main
                """.formatted(owner));

        migrator().migrateYaml();

        assertTrue(Files.isRegularFile(database));
        assertFalse(Files.exists(networksYaml));
        assertFalse(Files.exists(playerStateYaml));
        assertTrue(hasFileContaining("networks.yml.pre-sqlite-"));
        assertTrue(hasFileContaining("player-state.yml.pre-sqlite-"));
        assertTrue(hasFileContaining("networks.yml.migrated-"));
        assertTrue(hasFileContaining("player-state.yml.migrated-"));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            assertEquals(1L, count(statement, "networks"));
            assertEquals(1L, count(statement, "network_trusted"));
            assertEquals(1L, count(statement, "network_stats"));
            assertEquals(1L, count(statement, "player_state"));
            assertEquals("COMPLETE", metadata(statement, "migration_state"));
            assertEquals("3", metadata(statement, "migration_schema_version"));
            assertTrue(metadata(statement, "migration_source_hash").matches("[0-9a-f]{64}"));
        }
    }

    @Test
    void expiredTimedTrustIsDroppedInsteadOfBecomingPermanent() throws Exception {
        writeYaml("""
                networks:
                  Main:
                    owner: %s
                    trusted-expiry:
                      %s: %d
                """.formatted(owner, trusted, System.currentTimeMillis() - 1));

        migrator().migrateYaml();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            assertEquals(0L, count(statement, "network_trusted"));
            assertEquals("0", metadata(statement, "migration_trusted_count"));
        }
    }

    @Test
    void invalidYamlAbortsBeforePromotionAndPreservesSource() throws Exception {
        writeYaml("""
                networks:
                  Main:
                    owner: definitely-not-a-uuid
                """);

        assertThrows(StorageException.class, () -> migrator().migrateYaml());
        assertTrue(Files.exists(networksYaml));
        assertFalse(Files.exists(database));
    }

    @Test
    void malformedYamlAbortsBeforePromotionAndPreservesSource() throws Exception {
        writeYaml("""
                networks:
                  Main: [
                """);

        assertThrows(StorageException.class, () -> migrator().migrateYaml());
        assertTrue(Files.exists(networksYaml));
        assertFalse(Files.exists(database));
    }

    @Test
    void oversizedMigrationCountersAreRejectedWithoutPromotion() throws Exception {
        writeYaml("""
                networks:
                  Main:
                    owner: %s
                    stats:
                      %s:
                        name: Alex
                        deposited: 9223372036854775808
                        withdrawn: 0
                """.formatted(owner, owner));

        assertThrows(StorageException.class, () -> migrator().migrateYaml());
        assertTrue(Files.exists(networksYaml));
        assertFalse(Files.exists(database));
    }

    @Test
    void failedImportLeavesLockAndRecoverableTemporaryDatabase() throws Exception {
        writeYaml("""
                networks:
                  Main:
                    owner: %s
                """.formatted(owner));
        Files.writeString(playerStateYaml, """
                players:
                  %s:
                    selected-owned-network: Missing
                """.formatted(owner));

        assertThrows(StorageException.class, () -> migrator().migrateYaml());
        assertTrue(Files.exists(dataDirectory.resolve("migration.lock")));
        assertTrue(Files.exists(networksYaml));
        assertFalse(Files.exists(database));
        assertTrue(hasFileContaining(".failed-"));
    }

    @Test
    void packedLegacyDatabaseIsConvertedAndRetainedAsLegacyBackup() throws Exception {
        String serialized = """
                description: from-data
                trusted: []
                """;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE networks(name TEXT PRIMARY KEY, owner TEXT NOT NULL, description TEXT, data TEXT NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO networks(name, owner, description, data) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, "Legacy");
                insert.setString(2, owner.toString());
                insert.setString(3, "from-column");
                insert.setString(4, serialized);
                insert.executeUpdate();
            }
        }

        migrator().migrateLegacyDatabase(database.toFile());

        assertTrue(Files.exists(database));
        assertTrue(hasFileContaining("networks.db.legacy-"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            assertEquals(1L, count(statement, "networks"));
            assertEquals(0L, count(statement, "network_chests"));
            assertEquals("COMPLETE", metadata(statement, "migration_state"));
        }
    }

    private YamlToSqliteMigrator migrator() {
        NetworkAccessRules rules = new NetworkAccessRules() {
            @Override public boolean isGlobalNetworkMode() { return false; }
            @Override public boolean hasPrivilege(org.bukkit.command.CommandSender sender, String permission) { return false; }
            @Override public boolean isTrustSystemEnabled() { return true; }
        };
        return new YamlToSqliteMigrator(rules, MovementEvents.NOOP, dataDirectory.toFile(),
                networksYaml.toFile(), playerStateYaml.toFile(), database.toFile(),
                Logger.getLogger("migration-test"));
    }

    private void writeYaml(String content) throws Exception {
        Files.writeString(networksYaml, content);
    }

    private boolean hasFileContaining(String text) throws Exception {
        try (var files = Files.list(dataDirectory)) {
            return files.anyMatch(path -> path.getFileName().toString().contains(text));
        }
    }

    private long count(Statement statement, String table) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String metadata(Statement statement, String key) throws Exception {
        try (var query = connectionStatement(statement, key)) {
            assertTrue(query.next());
            return query.getString(1);
        }
    }

    private java.sql.ResultSet connectionStatement(Statement statement, String key) throws Exception {
        return statement.executeQuery("SELECT value FROM storage_metadata WHERE key = '" + key + "'");
    }
}
