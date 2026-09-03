package com.dermoha.networkstorage.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class Migrations {

    public static final int CURRENT_VERSION = 4;
    public static final int APPLICATION_ID = 0x4E53544F;

    private Migrations() {
    }

    public static List<SchemaMigration> all() {
        return List.of(new V1__InitialSchema(), new V2__Metadata(), new V3__RemoveRedundantIndexes(),
                new V4__PlayerSortType());
    }

    private static final class V1__InitialSchema implements SchemaMigration {

        @Override
        public int targetVersion() {
            return 1;
        }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (var s = connection.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS networks (
                      name TEXT PRIMARY KEY,
                      owner TEXT NOT NULL,
                      description TEXT NOT NULL DEFAULT ''
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS network_chests (
                      network_name TEXT NOT NULL REFERENCES networks(name) ON DELETE CASCADE,
                      world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      PRIMARY KEY (network_name, world, x, y, z)
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS network_terminals (
                      network_name TEXT NOT NULL REFERENCES networks(name) ON DELETE CASCADE,
                      world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      PRIMARY KEY (network_name, world, x, y, z)
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS network_senders (
                      network_name TEXT NOT NULL REFERENCES networks(name) ON DELETE CASCADE,
                      world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      PRIMARY KEY (network_name, world, x, y, z)
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS network_trusted (
                      network_name TEXT NOT NULL REFERENCES networks(name) ON DELETE CASCADE,
                      player_uuid TEXT NOT NULL,
                      expires_at INTEGER,
                      PRIMARY KEY (network_name, player_uuid)
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS network_stats (
                      network_name TEXT NOT NULL REFERENCES networks(name) ON DELETE CASCADE,
                      player_uuid TEXT NOT NULL,
                      player_name TEXT NOT NULL,
                      deposited INTEGER NOT NULL DEFAULT 0,
                      withdrawn INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (network_name, player_uuid)
                    )
                """);

                s.execute("""
                    CREATE TABLE IF NOT EXISTS player_state (
                      player_uuid TEXT PRIMARY KEY,
                      selected_network TEXT,
                      selected_wireless TEXT
                    )
                """);

                s.execute("CREATE INDEX IF NOT EXISTS idx_trusted_expires ON network_trusted(expires_at) WHERE expires_at IS NOT NULL");
            }
        }
    }

    /** Removes indexes made redundant by each table's primary-key prefix. */
    private static final class V3__RemoveRedundantIndexes implements SchemaMigration {
        @Override public int targetVersion() { return 3; }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (var s = connection.createStatement()) {
                s.execute("DROP INDEX IF EXISTS idx_chests_network");
                s.execute("DROP INDEX IF EXISTS idx_terminals_network");
                s.execute("DROP INDEX IF EXISTS idx_senders_network");
                s.execute("DROP INDEX IF EXISTS idx_trusted_network");
                s.execute("DROP INDEX IF EXISTS idx_stats_network");
                s.execute("DROP INDEX IF EXISTS idx_player_state");
            }
        }
    }

    /** Adds the per-player terminal sort preference. NULL means "use the server default". */
    private static final class V4__PlayerSortType implements SchemaMigration {
        @Override public int targetVersion() { return 4; }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE player_state ADD COLUMN sort_type TEXT");
            }
        }
    }

    private static final class V2__Metadata implements SchemaMigration {

        @Override
        public int targetVersion() {
            return 2;
        }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (var s = connection.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS storage_metadata (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                """);
            }
        }
    }
}
