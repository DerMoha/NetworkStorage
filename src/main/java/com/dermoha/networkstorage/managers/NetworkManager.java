package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.SortType;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkStorageProvider;
import com.dermoha.networkstorage.storage.PersistenceCoordinator;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.NetworkScanStatus;
import com.dermoha.networkstorage.storage.StorageSnapshot;
import com.dermoha.networkstorage.storage.StorageValues;
import com.dermoha.networkstorage.util.BlockUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public class NetworkManager {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final NetworkStorageProvider provider;
    private final PersistenceCoordinator persistence;
    private final Map<String, Network> networks = new ConcurrentHashMap<>();
    private final Map<Location, Network> locationIndex = new HashMap<>();
    private final Map<UUID, String> selectedNetworks = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedWirelessNetworks = new ConcurrentHashMap<>();
    private final Map<UUID, String> sortTypes = new ConcurrentHashMap<>();
    private final NetworkScanCoordinator scans;
    private final Object renameLock = new Object();
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public NetworkManager(NetworkStoragePlugin plugin, NetworkStorageProvider provider) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.provider = provider;
        this.persistence = new PersistenceCoordinator(provider, plugin.getLogger(),
                plugin.getConfigManager().getStorageWriteDebounceMs());
        this.scans = new NetworkScanCoordinator(plugin);
        loadAll();
        pruneInvalidPlayerState();
        for (Network network : networks.values()) {
            scans.attachNetwork(network);
            scans.getNetworkScan(network, true, null);
        }
    }

    public NetworkStorageProvider getStorageProvider() {
        return provider;
    }

    private Network newNetwork(String name, UUID owner) {
        return new Network(name, owner, plugin.getConfigManager(), plugin.getMovementEvents());
    }

    private void loadAll() {
        provider.loadNetworks(networks, selectedNetworks, selectedWirelessNetworks, sortTypes);

        boolean isGlobalMode = plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL;
        if (isGlobalMode && !networks.containsKey(GLOBAL_NETWORK_NAME)) {
            Network globalNetwork = newNetwork(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER);
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);

            plugin.getLogger().info("Created new global network in memory. It will be saved on next auto-save.");
        }

        rebuildLocationIndex();
    }

    private void rebuildLocationIndex() {
        locationIndex.clear();
        for (Network network : networks.values()) {
            for (Location loc : network.getChestLocations()) {
                locationIndex.put(loc, network);
            }
            for (Location loc : network.getTerminalLocations()) {
                locationIndex.put(loc, network);
            }
            for (Location loc : network.getSenderChestLocations()) {
                locationIndex.put(loc, network);
            }
        }
    }

    public void handleWorldLoaded(World world) {
        requirePrimaryThread();
        for (Network network : networks.values()) {
            if (!network.resolveUnloadedLocations(world)) {
                continue;
            }
            indexLocations(network);
            scans.getNetworkScan(network, true, null);
        }
    }

    private void indexLocations(Network network) {
        for (Location location : network.getChestLocations()) locationIndex.put(location, network);
        for (Location location : network.getTerminalLocations()) locationIndex.put(location, network);
        for (Location location : network.getSenderChestLocations()) locationIndex.put(location, network);
    }

    private void pruneInvalidPlayerState() {
        boolean changed = false;

        Iterator<Map.Entry<UUID, String>> selectedIterator = selectedNetworks.entrySet().iterator();
        while (selectedIterator.hasNext()) {
            Map.Entry<UUID, String> entry = selectedIterator.next();
            Network network = networks.get(entry.getValue());
            if (network == null || !network.getOwner().equals(entry.getKey())) {
                selectedIterator.remove();
                changed = true;
            }
        }

        Iterator<Map.Entry<UUID, String>> wirelessIterator = selectedWirelessNetworks.entrySet().iterator();
        while (wirelessIterator.hasNext()) {
            Map.Entry<UUID, String> entry = wirelessIterator.next();
            if (!networks.containsKey(entry.getValue())) {
                wirelessIterator.remove();
                changed = true;
            }
        }

        // Sort preferences are not network-scoped, so they stay valid for any player
        // who can open a terminal (owners and trusted players alike).

        if (changed) {
            requestPlayerStateSave();
        }
    }

    public void queueNetworkSave() {
        requirePrimaryThread();
        queueSnapshotNow();
    }

    public void queueFullSave() {
        requirePrimaryThread();
        queueSnapshotNow();
    }

    public void queuePlayerStateSave() {
        requirePrimaryThread();

        queueSnapshotNow();
    }

    private void requestPlayerStateSave() {
        if (Bukkit.isPrimaryThread()) {
            queuePlayerStateSave();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, this::queueSnapshotNow);
    }

    private void queueSnapshotNow() {
        // Capture Bukkit-owned state on the main thread; SQLite receives only detached values.
        persistence.request(StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks, sortTypes));
    }

    public PersistenceCoordinator.Status getPersistenceStatus() {
        return persistence.status();
    }

    public boolean hasPendingPersistence() {
        return persistence.isPending();
    }

    public String getLastPersistenceError() {
        return persistence.lastError();
    }

    public long getLastPersistenceSuccessAt() {
        return persistence.lastSuccessAt();
    }

    public boolean flushPersistence() {
        requirePrimaryThread();
        // Always capture the newest server-thread view before shutdown/reload waits for durability.
        persistence.request(StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks, sortTypes));
        boolean saved = persistence.flush(Duration.ofSeconds(30));
        if (saved) {
            for (Network network : networks.values()) network.setDirty(false);

        }
        return saved;
    }

    public void shutdownPersistence() {
        cancelScheduledScans();
        persistence.close();
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Network storage scans and Bukkit world access must run on the main thread");
        }
    }

    public NetworkScanResult getNetworkScan(Network network, boolean refresh,
                                            Consumer<NetworkScanResult> completionCallback) {
        return scans.getNetworkScan(network, refresh, completionCallback);
    }

    public NetworkScanResult getNetworkScan(Network network) {
        return getNetworkScan(network, false, null);
    }

    public NetworkScanResult rescanNetwork(Network network) {
        requirePrimaryThread();
        if (network == null) {
            return null;
        }
        network.invalidateItemCache();
        return getNetworkScan(network, true, null);
    }

    public void rescanAllNetworks() {
        requirePrimaryThread();
        for (Network network : getAllNetworks()) {
            rescanNetwork(network);
        }
    }

    public void cancelScheduledScans() {
        scans.cancelScheduledScans();
    }

    public boolean ensureLocationChunkLoaded(Location location) {
        requirePrimaryThread();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        try {
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                location.getWorld().loadChunk(chunkX, chunkZ, false);
            }
            return location.getWorld().isChunkLoaded(chunkX, chunkZ);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not load registered location chunk " + location, exception);
            return false;
        }
    }

    public int getStoredItemCountForMetrics() {
        long total = 0L;
        for (Network network : getAllNetworks()) {
            NetworkScanResult scan = network.getScanResult();
            if (scan.status() != NetworkScanStatus.COMPLETE || !scan.hasAuthoritativeData()) {
                return 0;
            }
            total += scan.totalItems();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public String getScanStatusForMetrics() {
        boolean pending = false;
        boolean incomplete = false;
        for (Network network : getAllNetworks()) {
            if (network.getScanResult().status() == NetworkScanStatus.INCOMPLETE) {
                incomplete = true;
            } else if (network.getScanResult().status() == NetworkScanStatus.PENDING) {
                pending = true;
            }
        }
        if (incomplete) {
            return NetworkScanStatus.INCOMPLETE.name().toLowerCase(java.util.Locale.ROOT);
        }
        if (pending) {
            return NetworkScanStatus.PENDING.name().toLowerCase(java.util.Locale.ROOT);
        }
        return NetworkScanStatus.COMPLETE.name().toLowerCase(java.util.Locale.ROOT);
    }

    public void addChestToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        scans.attachNetwork(network);
        network.addChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);

    }

    public void addTerminalToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        scans.attachNetwork(network);
        network.addTerminal(normalizedLocation);
        locationIndex.put(normalizedLocation, network);

    }

    public void addSenderChestToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        scans.attachNetwork(network);
        network.addSenderChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);

    }

    public boolean removeTrackedLocation(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        boolean changed = removeTrackedLocationExact(network, location);
        if (!normalizedLocation.equals(location)) {
            changed = removeTrackedLocationExact(network, normalizedLocation) || changed;
        }
        return changed;
    }

    private boolean removeTrackedLocationExact(Network network, Location location) {
        boolean changed = false;

        if (network.isChestInNetwork(location)) {
            network.removeChest(location);
            changed = true;
        }
        if (network.isTerminalInNetwork(location)) {
            network.removeTerminal(location);
            changed = true;
        }
        if (network.isSenderChestInNetwork(location)) {
            network.removeSenderChest(location);
            changed = true;
        }

        if (changed) {
            locationIndex.remove(location);
        }

        return changed;
    }

    public void createNetwork(Player player, String networkName) {
        requirePrimaryThread();
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.create.global_mode"));
            return;
        }
        if (!isValidNetworkName(networkName)) {
            player.sendMessage(lang.getMessage("network.name.invalid"));
            return;
        }
        if (findNetworkByNameIgnoreCase(networkName) != null) {
            player.sendMessage(lang.getMessage("network.create.exists"));
            return;
        }
        Network network = newNetwork(networkName, player.getUniqueId());
        scans.attachNetwork(network);
        networks.put(networkName, network);
        scans.getNetworkScan(network, true, null);

        selectedNetworks.put(player.getUniqueId(), networkName);

        queueNetworkSave();
        player.sendMessage(String.format(lang.getMessage("network.create.success"), networkName));
    }

    public void editNetwork(Player player, String networkName) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.edit.global_mode"));
            return;
        }
        if (!networks.containsKey(networkName)) {
            player.sendMessage(String.format(lang.getMessage("network.edit.not_found"), networkName));
            return;
        }
        player.sendMessage(lang.getMessage("network.edit.not_implemented"));
    }

    public void renameNetwork(Player player, String oldName, String newName) {
        requirePrimaryThread();
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.rename.global_mode"));
            return;
        }
        if (!isValidNetworkName(newName)) {
            player.sendMessage(lang.getMessage("network.name.invalid"));
            return;
        }
        if (!networks.containsKey(oldName)) {
            player.sendMessage(String.format(lang.getMessage("network.rename.not_found"), oldName));
            return;
        }
        if (oldName.equals(newName)) {
            player.sendMessage(String.format(lang.getMessage("network.rename.exists"), newName));
            return;
        }
        Network network = networks.get(oldName);
        if (com.dermoha.networkstorage.storage.NetworkNames.conflicts(networks.values(), newName, network)) {
            player.sendMessage(String.format(lang.getMessage("network.rename.exists"), newName));
            return;
        }

        if (!network.getOwner().equals(player.getUniqueId()) && !plugin.getConfigManager().hasPrivilege(player, "networkstorage.admin")) {
             player.sendMessage(lang.getMessage("network.rename.permission"));
             return;
        }

        synchronized (renameLock) {
            network.setName(newName);
            networks.put(newName, network);
            networks.remove(oldName);

            if (oldName.equals(selectedNetworks.get(network.getOwner()))) {
                selectedNetworks.put(network.getOwner(), newName);
            }
            for (Map.Entry<UUID, String> entry : selectedWirelessNetworks.entrySet()) {
                if (oldName.equals(entry.getValue())) {
                    entry.setValue(newName);
                }
            }
        }
        queueNetworkSave();
        player.sendMessage(String.format(lang.getMessage("network.rename.success"), oldName, newName));
    }

    public Network getNetwork(String name) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        return networks.get(name);
    }

    public Collection<Network> getAllNetworks() {
        return List.copyOf(networks.values());
    }

    public Map<String, Network> getNetworks() {
        return Map.copyOf(networks);
    }

    public Map<UUID, String> getSelectedWirelessNetworks() {
        return Map.copyOf(selectedWirelessNetworks);
    }

    public Network getNetworkByLocation(Location location) {
        requirePrimaryThread();
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Location normalizedLocation = getNormalizedLocation(location);
            Network globalNetwork = networks.get(GLOBAL_NETWORK_NAME);
            if (globalNetwork != null && containsTrackedLocation(globalNetwork, location, normalizedLocation)) {
                return globalNetwork;
            }
            return null;
        }

        Network indexed = locationIndex.get(location);
        if (indexed != null) {
            return indexed;
        }

        Location normalizedLocation = getNormalizedLocation(location);

        if (!normalizedLocation.equals(location)) {
            indexed = locationIndex.get(normalizedLocation);
            if (indexed != null) {
                return indexed;
            }
        }

        for (Network network : networks.values()) {
            if (containsTrackedLocation(network, location, normalizedLocation)) {
                return network;
            }
        }
        return null;
    }

    private boolean containsTrackedLocation(Network network, Location location, Location normalizedLocation) {
        return isTrackedLocation(network, location) || isTrackedLocation(network, normalizedLocation);
    }

    private boolean isTrackedLocation(Network network, Location location) {
        return network.isChestInNetwork(location)
                || network.isTerminalInNetwork(location)
                || network.isSenderChestInNetwork(location);
    }

    public Location getNormalizedLocation(Location location) {
        requirePrimaryThread();
        Block block = location.getBlock();
        if (!plugin.getConfigManager().isNetworkContainerBlock(block.getType())) {
            return location.clone();
        }
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return location.clone();
        }
        if (chestData.getType() == Chest.Type.RIGHT) {
            BlockFace direction = BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, chestData.getFacing());
            if (direction != null) {
                return block.getRelative(direction).getLocation();
            }
        }
        return location.clone();
    }

    public List<Network> getOwnedNetworks(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = networks.get(GLOBAL_NETWORK_NAME);
            return globalNetwork == null ? Collections.emptyList() : Collections.singletonList(globalNetwork);
        }

        String defaultNetworkName = player.getName() + "'s Network";
        return networks.values().stream()
                .filter(network -> network.getOwner().equals(player.getUniqueId()))
                .sorted(Comparator.comparing((Network network) -> !network.getName().equals(defaultNetworkName))
                        .thenComparing(Network::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Network::getName))
                .toList();
    }

    public List<Network> getAccessibleNetworks(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = networks.get(GLOBAL_NETWORK_NAME);
            return globalNetwork == null ? Collections.emptyList() : Collections.singletonList(globalNetwork);
        }

        String defaultNetworkName = player.getName() + "'s Network";
        return networks.values().stream()
                .filter(network -> network.canAccess(player))
                .sorted(Comparator.comparing((Network network) -> !network.getOwner().equals(player.getUniqueId()))
                        .thenComparing(network -> !network.getName().equals(defaultNetworkName))
                        .thenComparing(Network::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Network::getName))
                .toList();
    }

    public Network findOwnedNetwork(Player player, String networkName) {
        return com.dermoha.networkstorage.storage.NetworkNames.find(getOwnedNetworks(player), networkName);
    }

    public Network findAccessibleNetwork(Player player, String networkName) {
        return com.dermoha.networkstorage.storage.NetworkNames.find(getAccessibleNetworks(player), networkName);
    }

    private Network findNetworkByNameIgnoreCase(String networkName) {
        if (networkName == null) {
            return null;
        }
        return networks.values().stream()
                .filter(network -> network.getName().equalsIgnoreCase(networkName))
                .findFirst()
                .orElse(null);
    }

    public boolean selectPlayerNetwork(Player player, String networkName) {
        Network selectedNetwork = findOwnedNetwork(player, networkName);
        if (selectedNetwork == null) {
            return false;
        }

        selectedNetworks.put(player.getUniqueId(), selectedNetwork.getName());
        queuePlayerStateSave();
        return true;
    }

    public boolean selectWirelessNetwork(Player player, String networkName) {
        Network selectedNetwork = findAccessibleNetwork(player, networkName);
        if (selectedNetwork == null) {
            return false;
        }

        selectedWirelessNetworks.put(player.getUniqueId(), selectedNetwork.getName());
        queuePlayerStateSave();
        return true;
    }

    public Network getSelectedWirelessNetwork(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }

        String selectedName = selectedWirelessNetworks.get(player.getUniqueId());
        if (selectedName == null) {
            return null;
        }

        Network selectedNetwork = networks.get(selectedName);
        if (selectedNetwork != null && selectedNetwork.canAccess(player)) {
            return selectedNetwork;
        }

        selectedWirelessNetworks.remove(player.getUniqueId());
        requestPlayerStateSave();
        return null;
    }

    public String getSelectedWirelessNetworkName(Player player) {
        Network selectedNetwork = getSelectedWirelessNetwork(player);
        return selectedNetwork == null ? null : selectedNetwork.getName();
    }

    public SortType getPlayerSortType(Player player, SortType fallback) {
        String stored = sortTypes.get(player.getUniqueId());
        if (stored == null) {
            return fallback;
        }
        try {
            return SortType.valueOf(stored);
        } catch (IllegalArgumentException e) {
            sortTypes.remove(player.getUniqueId());
            return fallback;
        }
    }

    public void setPlayerSortType(Player player, SortType type) {
        requirePrimaryThread();
        sortTypes.put(player.getUniqueId(), type.name());
        queuePlayerStateSave();
    }

    public void clearPlayerSortType(Player player) {
        requirePrimaryThread();
        if (sortTypes.remove(player.getUniqueId()) != null) {
            queuePlayerStateSave();
        }
    }

    public String getNetworkOwnerName(Network network) {
        if (network == null) {
            return "";
        }
        if (GLOBAL_NETWORK_OWNER.equals(network.getOwner())) {
            return lang.getMessage("network.global_owner");
        }

        OfflinePlayer owner = plugin.getServer().getOfflinePlayer(network.getOwner());
        return owner.getName() != null ? owner.getName() : network.getOwner().toString();
    }

    public Network getPlayerNetwork(Player player) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }

        String selectedName = selectedNetworks.get(player.getUniqueId());
        if (selectedName != null) {
            Network selectedNetwork = networks.get(selectedName);
            if (selectedNetwork != null && selectedNetwork.getOwner().equals(player.getUniqueId())) {
                return selectedNetwork;
            }
            selectedNetworks.remove(player.getUniqueId());
            requestPlayerStateSave();
        }

        List<Network> ownedNetworks = getOwnedNetworks(player);
        return ownedNetworks.isEmpty() ? null : ownedNetworks.get(0);
    }

    public synchronized Network getOrCreatePlayerNetwork(Player player) {
        requirePrimaryThread();
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        Network network = getPlayerNetwork(player);
        if (network == null) {
            String networkName = player.getName() + "'s Network";
            if (!isValidNetworkName(networkName)) {
                player.sendMessage(lang.getMessage("network.name.invalid"));
                return null;
            }
            Network conflictingNetwork = findNetworkByNameIgnoreCase(networkName);
            if (conflictingNetwork != null) {
                if (conflictingNetwork.getOwner().equals(player.getUniqueId())) return conflictingNetwork;
                player.sendMessage(lang.getMessage("network.orcreate.exists"));
                return null;
            }
            network = newNetwork(networkName, player.getUniqueId());
            scans.attachNetwork(network);
            networks.put(networkName, network);
            scans.getNetworkScan(network, true, null);

        }
        return network;
    }

    public boolean isValidNetworkName(String networkName) {
        return StorageValues.isValidNetworkName(networkName);
    }

    public synchronized int purgeAllNetworksForSeasonReset() {
        requirePrimaryThread();
        List<Network> networksToPurge = new ArrayList<>(networks.values());
        if (networksToPurge.isEmpty()) {
            return 0;
        }

        for (Network network : networksToPurge) {
            archiveDeletedNetworkStats(network.getName(), network);
            clearNetworkChestContents(network);
            scans.detachNetwork(network);
            resetNetworkInternal(network);
        }

        networks.clear();
        locationIndex.clear();
        selectedNetworks.clear();
        selectedWirelessNetworks.clear();
        sortTypes.clear();

        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = newNetwork(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER);
            scans.attachNetwork(globalNetwork);
            scans.getNetworkScan(globalNetwork, true, null);

            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
        }

        queueFullSave();
        return networksToPurge.size();
    }

    public synchronized boolean deleteNetwork(Player player, String networkName) {
        requirePrimaryThread();
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.delete.global_mode"));
            return false;
        }
        if (GLOBAL_NETWORK_NAME.equals(networkName)) {
            player.sendMessage(lang.getMessage("network.delete.protected"));
            return false;
        }
        Network network = networks.get(networkName);
        if (network == null) {
            player.sendMessage(String.format(lang.getMessage("network.delete.not_found"), networkName));
            return false;
        }
        if (!network.getOwner().equals(player.getUniqueId())
                && !plugin.getConfigManager().hasPrivilege(player, "networkstorage.admin")) {
            player.sendMessage(lang.getMessage("network.delete.permission"));
            return false;
        }

        Set<Location> chestLocationsToClear = network.getChestLocations();
        scans.detachNetwork(network);
        resetNetworkInternal(network);
        networks.remove(networkName);

        selectedNetworks.entrySet().removeIf(entry -> networkName.equals(entry.getValue()));
        selectedWirelessNetworks.entrySet().removeIf(entry -> networkName.equals(entry.getValue()));

        queueNetworkSave();
        archiveDeletedNetworkStats(networkName, network);
        clearNetworkChestContents(chestLocationsToClear);
        player.sendMessage(String.format(lang.getMessage("network.delete.success"), networkName));
        return true;
    }

    private void archiveDeletedNetworkStats(String networkName, Network network) {
        if (network.getPlayerStats().isEmpty()) {
            return;
        }
        java.io.File archiveFile = new java.io.File(plugin.getDataFolder(), "deleted-networks.log");
        try (java.io.FileWriter writer = new java.io.FileWriter(archiveFile, true)) {
            writer.write("[" + new java.util.Date() + "] DELETED NETWORK: " + networkName + "\n");
            for (java.util.Map.Entry<java.util.UUID, com.dermoha.networkstorage.stats.PlayerStat> entry : network.getPlayerStats().entrySet()) {
                com.dermoha.networkstorage.stats.PlayerStat stat = entry.getValue();
                writer.write("  - " + stat.getPlayerName() + " (" + entry.getKey() + "): deposited=" + stat.getItemsDeposited() + " withdrawn=" + stat.getItemsWithdrawn() + "\n");
            }
            writer.write("\n");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not write to deleted-networks log: " + e.getMessage());
        }
    }

    private void clearNetworkChestContents(Network network) {
        clearNetworkChestContents(network.getChestLocations());
        network.resetTotalStoredAmount();
    }

    private void clearNetworkChestContents(Collection<Location> locations) {
        for (Location location : locations) {
            if (!ensureLocationChunkLoaded(location)) {
                continue;
            }
            if (!(location.getBlock().getState() instanceof Container container)) {
                continue;
            }
            container.getInventory().clear();
            container.update();
        }
    }

    public boolean resetNetwork(Network network) {
        requirePrimaryThread();
        resetNetworkInternal(network);

        queueNetworkSave();
        return true;
    }

    private void resetNetworkInternal(Network network) {
        for (Location location : network.getChestLocations()) {
            removeTrackedLocationExact(network, location);
        }
        for (Location location : network.getTerminalLocations()) {
            removeTrackedLocationExact(network, location);
        }
        for (Location location : network.getSenderChestLocations()) {
            removeTrackedLocationExact(network, location);
        }
        network.clearUnloadedLocations();
    }
}
