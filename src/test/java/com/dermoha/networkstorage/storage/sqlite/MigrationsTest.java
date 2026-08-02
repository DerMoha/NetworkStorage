package com.dermoha.networkstorage.storage.sqlite;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationsTest {

    @Test
    void initialMigrationCreatesAllTablesAndIndexes() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            for (SchemaMigration migration : Migrations.all()) {
                migration.migrate(conn);
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("PRAGMA user_version = " + migration.targetVersion());
                }
            }

            String[] expectedTables = {
                    "networks", "network_chests", "network_terminals", "network_senders",
                    "network_trusted", "network_stats", "player_state", "storage_metadata"
            };
            for (String table : expectedTables) {
                try (Statement s = conn.createStatement();
                     ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(rs.next(), "expected table " + table + " to exist");
                }
            }

            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_trusted_expires'")) {
                assertTrue(rs.next(), "expected partial index idx_trusted_expires to exist");
            }
        }
    }

    @Test
    void migrationTargetVersionsReachCurrentSchema() {
        assertEquals(3, Migrations.CURRENT_VERSION);
        assertEquals(1, Migrations.all().get(0).targetVersion());
        assertEquals(2, Migrations.all().get(1).targetVersion());
        assertEquals(3, Migrations.all().get(2).targetVersion());
    }
}
