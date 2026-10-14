package com.dermoha.networkstorage.storage.sqlite;

import com.dermoha.networkstorage.storage.StorageSnapshot;
import com.dermoha.networkstorage.storage.StorageValues;
import java.sql.*;
import java.util.*;

/** Snapshot row mapping only; the provider owns locks, transactions and connections. */
final class SnapshotSqlWriter {
    private SnapshotSqlWriter() {}
    static void clearSnapshotTables(Connection c) throws SQLException {
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

    static void insertSnapshot(Connection c, StorageSnapshot snapshot) throws SQLException {
        Set<String> networkNames = new HashSet<>();
        for (StorageSnapshot.NetworkData network : snapshot.networks()) {
            if (network == null || network.name() == null || network.name().isBlank()) {
                throw new SQLException("Snapshot contains a network with no valid name");
            }
            if (!networkNames.add(network.name())) {
                throw new SQLException("Snapshot contains duplicate network '" + network.name() + "'");
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
                     "INSERT INTO player_state(player_uuid, selected_network, selected_wireless, sort_type) VALUES(?, ?, ?, ?)")) {

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
            playerIds.addAll(snapshot.sortTypes().keySet());
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
                String sort = snapshot.sortTypes().get(playerId);
                stateInsert.setString(1, playerId.toString());
                stateInsert.setString(2, selectedNetwork);
                stateInsert.setString(3, selectedWireless);
                if (sort == null) {
                    stateInsert.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    stateInsert.setString(4, sort);
                }
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

    private static void insertLocations(PreparedStatement insert,
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

}
