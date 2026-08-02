package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * An immutable persistence boundary.  This object deliberately contains no Bukkit
 * objects, so SQLite work can safely run away from the server thread.
 */
public record StorageSnapshot(List<NetworkData> networks,
                              Map<UUID, String> selectedNetworks,
                              Map<UUID, String> selectedWirelessNetworks) {

    public record LocationData(String world, int x, int y, int z) {}
    public record TrustedPlayer(UUID playerId, Long expiresAt) {}
    public record PlayerStatData(UUID playerId, String playerName, long deposited, long withdrawn) {}
    public record NetworkData(String name, UUID owner, String description,
                              List<LocationData> chests, List<LocationData> terminals,
                              List<LocationData> senders, List<TrustedPlayer> trusted,
                              List<PlayerStatData> stats) {}

    public StorageSnapshot {
        networks = List.copyOf(networks);
        selectedNetworks = Map.copyOf(selectedNetworks);
        selectedWirelessNetworks = Map.copyOf(selectedWirelessNetworks);
    }

    public static StorageSnapshot capture(Collection<Network> networks,
                                          Map<UUID, String> selectedNetworks,
                                          Map<UUID, String> selectedWirelessNetworks) {
        List<NetworkData> copied = new ArrayList<>(networks.size());
        for (Network network : networks) {
            List<TrustedPlayer> trusted = new ArrayList<>();
            Map<UUID, Long> expiries = network.getTrustedPlayersWithExpiry();
            for (UUID playerId : network.getTrustedPlayers()) {
                trusted.add(new TrustedPlayer(playerId, expiries.get(playerId)));
            }
            List<PlayerStatData> stats = new ArrayList<>();
            for (PlayerStat stat : network.getPlayerStats().values()) {
                stats.add(new PlayerStatData(stat.getPlayerUUID(), stat.getPlayerName(),
                        stat.getItemsDeposited(), stat.getItemsWithdrawn()));
            }
            copied.add(new NetworkData(network.getName(), network.getOwner(), network.getDescription(),
                    copyLocations(network.getChestLocations()), copyLocations(network.getTerminalLocations()),
                    copyLocations(network.getSenderChestLocations()), trusted, stats));
        }
        return new StorageSnapshot(copied, copyState(selectedNetworks), copyState(selectedWirelessNetworks));
    }

    private static List<LocationData> copyLocations(Collection<Location> locations) {
        List<LocationData> copied = new ArrayList<>(locations.size());
        for (Location location : locations) {
            copied.add(location == null || location.getWorld() == null
                    ? new LocationData(null, 0, 0, 0)
                    : new LocationData(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        }
        return List.copyOf(copied);
    }

    private static Map<UUID, String> copyState(Map<UUID, String> source) {
        Map<UUID, String> copy = new HashMap<>();
        for (Map.Entry<UUID, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Player-state maps cannot contain null keys or values");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    public long networkCount() { return networks.size(); }
    public long chestCount() { return networks.stream().mapToLong(n -> n.chests().size()).sum(); }
    public long terminalCount() { return networks.stream().mapToLong(n -> n.terminals().size()).sum(); }
    public long senderCount() { return networks.stream().mapToLong(n -> n.senders().size()).sum(); }
    public long trustedCount() { return networks.stream().mapToLong(n -> n.trusted().size()).sum(); }
    public long statsCount() { return networks.stream().mapToLong(n -> n.stats().size()).sum(); }
    public long playerStateCount() {
        Set<UUID> ids = new HashSet<>(selectedNetworks.keySet());
        ids.addAll(selectedWirelessNetworks.keySet());
        return ids.size();
    }

    public String digest() {
        try {
            List<String> rows = new ArrayList<>();
            for (NetworkData network : networks) {
                rows.add("network|" + network.name() + "|" + network.owner() + "|" + network.description());
                addLocationRows(rows, "chest", network.name(), network.chests());
                addLocationRows(rows, "terminal", network.name(), network.terminals());
                addLocationRows(rows, "sender", network.name(), network.senders());
                for (TrustedPlayer trusted : network.trusted()) {
                    rows.add("trusted|" + network.name() + "|" + trusted.playerId() + "|"
                            + (trusted.expiresAt() == null ? "" : trusted.expiresAt()));
                }
                for (PlayerStatData stat : network.stats()) {
                    rows.add("stat|" + network.name() + "|" + stat.playerId() + "|" + stat.playerName()
                            + "|" + stat.deposited() + "|" + stat.withdrawn());
                }
            }
            Set<UUID> ids = new HashSet<>(selectedNetworks.keySet());
            ids.addAll(selectedWirelessNetworks.keySet());
            for (UUID id : ids) {
                rows.add("player|" + id + "|" + selectedNetworks.getOrDefault(id, "") + "|"
                        + selectedWirelessNetworks.getOrDefault(id, ""));
            }
            rows.sort(String::compareTo);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String row : rows) {
                digest.update(row.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void addLocationRows(List<String> rows, String type, String network, List<LocationData> locations) {
        for (LocationData location : locations) {
            rows.add(type + "|" + network + "|" + location.world() + "|" + location.x() + "|" + location.y() + "|" + location.z());
        }
    }
}
