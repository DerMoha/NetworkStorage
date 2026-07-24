package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public class SqliteNetworkStorageProvider implements NetworkStorageProvider {

    private final NetworkStoragePlugin plugin;
    private final File databaseFile;
    private final ReentrantLock writeLock = new ReentrantLock();
    private Connection connection;
    private boolean available;

    public SqliteNetworkStorageProvider(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "networks.db");
    }

    @Override
    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS networks (" +
                        "name TEXT PRIMARY KEY, " +
                        "owner TEXT NOT NULL, " +
                        "description TEXT, " +
                        "data TEXT NOT NULL)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS player_state (" +
                        "player_uuid TEXT PRIMARY KEY, " +
                        "selected_network TEXT, " +
                        "selected_wireless TEXT)");
            }
            available = true;
            plugin.getLogger().info("SQLite storage provider initialized at " + databaseFile.getAbsolutePath());
        } catch (Exception e) {
            available = false;
            plugin.getLogger().log(Level.WARNING, "Could not initialize SQLite storage provider; falling back to YAML.", e);
        }
    }

    @Override
    public void shutdown() {
        writeLock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not close SQLite connection: " + e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void loadNetworks(Map<String, Network> networks,
                             Map<Location, Network> locationIndex,
                             Map<UUID, String> selectedNetworks,
                             Map<UUID, String> selectedWirelessNetworks,
                             OfflinePlayer offlinePlayerLookup) {
        if (!available) {
            return;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, owner, description, data FROM networks")) {
            while (rs.next()) {
                try {
                    String name = rs.getString("name");
                    UUID owner = UUID.fromString(rs.getString("owner"));
                    String description = rs.getString("description");
                    String data = rs.getString("data");

                    FileConfiguration snapshot = YamlConfiguration.loadConfiguration(new java.io.StringReader(data));
                    Network network = new Network(name, owner, plugin.getConfigManager(), plugin.getMovementEvents());
                    if (description != null) {
                        network.setDescription(description);
                    }

                    for (Map<?, ?> locMap : snapshot.getMapList("chests")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addChest(location);
                        }
                    }
                    for (Map<?, ?> locMap : snapshot.getMapList("terminals")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addTerminal(location);
                        }
                    }
                    for (Map<?, ?> locMap : snapshot.getMapList("sender-chests")) {
                        Location location = deserializeLocation(locMap);
                        if (location != null) {
                            network.addSenderChest(location);
                        }
                    }
                    for (String trustedUuid : snapshot.getStringList("trusted")) {
                        try {
                            network.addTrustedPlayer(UUID.fromString(trustedUuid));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Skipping invalid trusted UUID in SQLite: " + trustedUuid);
                        }
                    }
                    ConfigurationSection statsSection = snapshot.getConfigurationSection("stats");
                    if (statsSection != null) {
                        for (String uuidString : statsSection.getKeys(false)) {
                            try {
                                UUID playerUUID = UUID.fromString(uuidString);
                                String statName = statsSection.getString(uuidString + ".name");
                                long deposited = Math.max(0L, statsSection.getLong(uuidString + ".deposited"));
                                long withdrawn = Math.max(0L, statsSection.getLong(uuidString + ".withdrawn"));
                                PlayerStat stat = new PlayerStat(playerUUID, statName, deposited, withdrawn);
                                network.getPlayerStats().put(playerUUID, stat);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Skipping invalid stats UUID in SQLite: " + uuidString);
                            }
                        }
                    }
                    network.setDirty(false);
                    networks.put(name, network);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not load network from SQLite row: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not load networks from SQLite.", e);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid, selected_network, selected_wireless FROM player_state")) {
            while (rs.next()) {
                try {
                    UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                    String selected = rs.getString("selected_network");
                    String selectedWireless = rs.getString("selected_wireless");
                    if (selected != null) {
                        selectedNetworks.put(playerId, selected);
                    }
                    if (selectedWireless != null) {
                        selectedWirelessNetworks.put(playerId, selectedWireless);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping invalid player-state row: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not load player state from SQLite.", e);
        }
    }

    @Override
    public void saveNetworks(Collection<Network> networks) {
        if (!available) {
            return;
        }
        writeLock.lock();
        try {
            try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM networks");
                 PreparedStatement insertStmt = connection.prepareStatement(
                         "INSERT INTO networks(name, owner, description, data) VALUES(?, ?, ?, ?)")) {
                connection.setAutoCommit(false);
                deleteStmt.executeUpdate();
                for (Network network : networks) {
                    FileConfiguration snapshot = new YamlConfiguration();
                    List<Map<String, Object>> chests = new java.util.ArrayList<>();
                    for (Location loc : network.getChestLocations()) {
                        chests.add(loc.serialize());
                    }
                    snapshot.set("chests", chests);
                    List<Map<String, Object>> terminals = new java.util.ArrayList<>();
                    for (Location loc : network.getTerminalLocations()) {
                        terminals.add(loc.serialize());
                    }
                    snapshot.set("terminals", terminals);
                    List<Map<String, Object>> senders = new java.util.ArrayList<>();
                    for (Location loc : network.getSenderChestLocations()) {
                        senders.add(loc.serialize());
                    }
                    snapshot.set("sender-chests", senders);
                    snapshot.set("trusted", network.getTrustedPlayers().stream().map(UUID::toString).toList());
                    if (network.getDescription() != null && !network.getDescription().isEmpty()) {
                        snapshot.set("description", network.getDescription());
                    }
                    for (PlayerStat stat : network.getPlayerStats().values()) {
                        String basePath = "stats." + stat.getPlayerUUID();
                        snapshot.set(basePath + ".name", stat.getPlayerName());
                        snapshot.set(basePath + ".deposited", stat.getItemsDeposited());
                        snapshot.set(basePath + ".withdrawn", stat.getItemsWithdrawn());
                    }
                    String data = snapshot.saveToString();
                    insertStmt.setString(1, network.getName());
                    insertStmt.setString(2, network.getOwner().toString());
                    insertStmt.setString(3, network.getDescription());
                    insertStmt.setString(4, data);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
                connection.commit();
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save networks to SQLite.", e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void savePlayerState(Map<UUID, String> selectedNetworks, Map<UUID, String> selectedWirelessNetworks) {
        if (!available) {
            return;
        }
        writeLock.lock();
        try {
            try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM player_state");
                 PreparedStatement insertStmt = connection.prepareStatement(
                         "INSERT OR REPLACE INTO player_state(player_uuid, selected_network, selected_wireless) VALUES(?, ?, ?)")) {
                connection.setAutoCommit(false);
                deleteStmt.executeUpdate();
                Map<UUID, String[]> combined = new LinkedHashMap<>();
                for (Map.Entry<UUID, String> e : selectedNetworks.entrySet()) {
                    combined.computeIfAbsent(e.getKey(), k -> new String[2]);
                    combined.get(e.getKey())[0] = e.getValue();
                }
                for (Map.Entry<UUID, String> e : selectedWirelessNetworks.entrySet()) {
                    combined.computeIfAbsent(e.getKey(), k -> new String[2]);
                    combined.get(e.getKey())[1] = e.getValue();
                }
                for (Map.Entry<UUID, String[]> entry : combined.entrySet()) {
                    insertStmt.setString(1, entry.getKey().toString());
                    insertStmt.setString(2, entry.getValue()[0]);
                    insertStmt.setString(3, entry.getValue()[1]);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
                connection.commit();
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player state to SQLite.", e);
        } finally {
            writeLock.unlock();
        }
    }

    private Location deserializeLocation(Map<?, ?> locMap) {
        try {
            Map<String, Object> serializedLocation = new HashMap<>();
            for (Map.Entry<?, ?> entry : locMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    serializedLocation.put(key, entry.getValue());
                }
            }
            Location location = Location.deserialize(serializedLocation);
            if (location.getWorld() == null
                    || !Double.isFinite(location.getX())
                    || !Double.isFinite(location.getY())
                    || !Double.isFinite(location.getZ())) {
                return null;
            }
            World world = Bukkit.getWorld(location.getWorld().getName());
            if (world == null) {
                return null;
            }
            location.setWorld(world);
            return location;
        } catch (Exception e) {
            return null;
        }
    }
}
