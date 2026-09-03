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
        assertEquals(4, Migrations.CURRENT_VERSION);
        assertEquals(1, Migrations.all().get(0).targetVersion());
        assertEquals(2, Migrations.all().get(1).targetVersion());
        assertEquals(3, Migrations.all().get(2).targetVersion());
        assertEquals(4, Migrations.all().get(3).targetVersion());
    }

    @Test
    void v4AddsSortTypeColumnToPlayerState() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            for (SchemaMigration migration : Migrations.all()) {
                migration.migrate(conn);
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("PRAGMA user_version = " + migration.targetVersion());
                }
            }

            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA table_info(player_state)")) {
                boolean found = false;
                while (rs.next()) {
                    if ("sort_type".equals(rs.getString("name"))) {
                        found = true;
                    }
                }
                assertTrue(found, "expected player_state.sort_type column to exist");
            }
        }
    }

    @Test
    void v4MigrationAppliesToExistingV3Database() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            for (SchemaMigration migration : Migrations.all().subList(0, 3)) {
                migration.migrate(conn);
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("PRAGMA user_version = " + migration.targetVersion());
                }
            }

            try (Statement s = conn.createStatement()) {
                s.executeUpdate("INSERT INTO player_state(player_uuid, selected_network, selected_wireless) "
                        + "VALUES('00000000-0000-0000-0000-000000000001', 'net', NULL)");
            }

            Migrations.all().get(3).migrate(conn);
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT selected_network, sort_type FROM player_state WHERE player_uuid = '00000000-0000-0000-0000-000000000001'")) {
                assertTrue(rs.next(), "expected existing player_state row to survive the migration");
                assertEquals("net", rs.getString("selected_network"));
                assertTrue(rs.getString("sort_type") == null, "expected sort_type to be NULL for existing rows");
            }
        }
    }
}
