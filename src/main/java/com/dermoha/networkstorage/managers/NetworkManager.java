package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
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
import java.util.Objects;
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
    private final Set<String> dirtyNetworks = ConcurrentHashMap.newKeySet();
    private final NetworkContentScanner contentScanner = new NetworkContentScanner();
    private final Map<Network, ScanJob> scanJobs = new java.util.IdentityHashMap<>();
    private final Set<Network> attachedNetworks = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean scansCancelled;
    private volatile boolean storageDirty;
    private volatile boolean playerStateDirty;
    private final Object renameLock = new Object();
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public NetworkManager(NetworkStoragePlugin plugin, NetworkStorageProvider provider) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.provider = provider;
        this.persistence = new PersistenceCoordinator(provider, plugin.getLogger(),
                plugin.getConfigManager().getStorageWriteDebounceMs());
        loadAll();
        pruneInvalidPlayerState();
        for (Network network : networks.values()) {
            attachNetwork(network);
            requestScan(network, true, null);
        }
    }

    public NetworkStorageProvider getStorageProvider() {
        return provider;
    }

    private void loadAll() {
        provider.loadNetworks(networks, selectedNetworks, selectedWirelessNetworks);

        boolean isGlobalMode = plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL;
        if (isGlobalMode && !networks.containsKey(GLOBAL_NETWORK_NAME)) {
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER, plugin.getConfigManager());
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
            markDirty(GLOBAL_NETWORK_NAME);
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
            requestScan(network, true, null);
        }
    }

    private void indexLocations(Network network) {
        for (Location location : network.getChestLocations()) locationIndex.put(location, network);
        for (Location location : network.getTerminalLocations()) locationIndex.put(location, network);
        for (Location location : network.getSenderChestLocations()) locationIndex.put(location, network);
    }

    private void markDirty(String name) {
        storageDirty = true;
        if (name != null) {
            dirtyNetworks.add(name);
        }
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

        if (changed) {
            requestPlayerStateSave();
        }
    }

    public boolean saveNetworks() {
        requirePrimaryThread();
        if (!hasPendingChanges()) {
            return true;
        }
        return saveSnapshotNow();
    }

    public boolean saveAllNetworks() {
        requirePrimaryThread();
        return saveSnapshotNow();
    }

    public boolean savePlayerState() {
        requirePrimaryThread();
        playerStateDirty = true;
        return saveSnapshotNow();
    }

    private void requestPlayerStateSave() {
        if (Bukkit.isPrimaryThread()) {
            savePlayerState();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            playerStateDirty = true;
            saveSnapshotNow();
        });
    }

    private boolean hasPendingChanges() {
        if (storageDirty || playerStateDirty) {
            return true;
        }
        return networks.values().stream().anyMatch(Network::isDirty);
    }

    private boolean saveSnapshotNow() {
        // Capture Bukkit-owned state on the main thread; SQLite receives only detached values.
        persistence.request(StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks));
        return true;
    }

    public boolean flushPersistence() {
        requirePrimaryThread();
        // Always capture the newest server-thread view before shutdown/reload waits for durability.
        persistence.request(StorageSnapshot.capture(networks.values(), selectedNetworks, selectedWirelessNetworks));
        boolean saved = persistence.flush(Duration.ofSeconds(30));
        if (saved) {
            for (Network network : networks.values()) network.setDirty(false);
            dirtyNetworks.clear();
            storageDirty = false;
            playerStateDirty = false;
        }
        return saved;
    }

    public void shutdownPersistence() {
        cancelScheduledScans();
        persistence.close();
    }

    /**
     * Connects a network's content mutations to the coalesced main-thread
     * scanner.  The callback deliberately does not read Bukkit state; it only
     * schedules the scan if a caller ever mutates a network off-thread.
     */
    private void attachNetwork(Network network) {
        if (network == null || !attachedNetworks.add(network)) {
            return;
        }
        network.setContentChangeListener(() -> {
            if (Bukkit.isPrimaryThread()) {
                requestScan(network, false, null);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> requestScan(network, false, null));
            }
        });
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Network storage scans and Bukkit world access must run on the main thread");
        }
    }

    private void requestScan(Network network,
                             boolean refresh,
                             Consumer<NetworkScanResult> completionCallback) {
        getNetworkScan(network, refresh, completionCallback);
    }

    /**
     * Returns the latest authoritative snapshot, starting a scan when the
     * snapshot is missing or stale.  A refresh always starts a fresh scan;
     * otherwise an existing scan is coalesced and allowed to finish.
     */
    public NetworkScanResult getNetworkScan(Network network,
                                             boolean refresh,
                                             Consumer<NetworkScanResult> completionCallback) {
        requirePrimaryThread();
        if (network == null) {
            return null;
        }
        attachNetworkIfNeeded(network);

        ScanJob existing = scanJobs.get(network);
        if (refresh) {
            if (existing != null) {
                restartScan(existing, completionCallback);
            } else {
                startScan(network, completionCallback);
            }
            return network.getScanResult();
        }

        NetworkScanResult current = network.getScanResult();
        if (current.status() == NetworkScanStatus.COMPLETE) {
            if (completionCallback != null) {
                completionCallback.accept(current);
            }
            return current;
        }

        // An incomplete result is an actionable diagnostic, not a request to
        // retry forever on every terminal render.  Content invalidation and
        // the explicit admin/terminal refresh path both create a fresh scan.
        if (current.status() == NetworkScanStatus.INCOMPLETE && existing == null) {
            return current;
        }

        if (existing == null) {
            startScan(network, completionCallback);
        } else if (completionCallback != null) {
            if (!existing.callbacks().contains(completionCallback)) {
                existing.callbacks().add(completionCallback);
            }
        }
        return network.getScanResult();
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

    private void startScan(Network network, Consumer<NetworkScanResult> callback) {
        requirePrimaryThread();
        if (scansCancelled) {
            return;
        }
        NetworkContentScanner.ScanSession session = contentScanner.begin(
                network.getName(), network.getChestLocations(), network.getUnloadedChestLocations());
        network.beginScan(session.registeredLocations(), session.uniqueChunks());
        ScanJob job = new ScanJob(network, session, network.getContentVersion());
        if (callback != null) {
            job.callbacks().add(callback);
        }
        scanJobs.put(network, job);
        scheduleScanStep(job);
    }

    private void restartScan(ScanJob job, Consumer<NetworkScanResult> callback) {
        requirePrimaryThread();
        List<Consumer<NetworkScanResult>> callbacks = new ArrayList<>(job.callbacks());
        if (callback != null) {
            callbacks.add(callback);
        }
        cancelScan(job.network());
        startScan(job.network(), null);
        ScanJob replacement = scanJobs.get(job.network());
        if (replacement != null) {
            replacement.callbacks().addAll(callbacks);
        }
    }

    private void scheduleScanStep(ScanJob job) {
        job.taskId = plugin.getServer().getScheduler().runTask(plugin, () -> advanceScan(job)).getTaskId();
    }

    private void advanceScan(ScanJob job) {
        requirePrimaryThread();
        if (scanJobs.get(job.network()) != job || scansCancelled) {
            return;
        }
        if (job.network().getContentVersion() != job.contentVersion()) {
            restartScan(job, null);
            return;
        }

        NetworkContentScanner.ScanStep step = contentScanner.advance(
                job.session(), NetworkContentScanner.DEFAULT_CHUNKS_PER_TICK);
        if (!step.complete()) {
            scheduleScanStep(job);
            return;
        }

        if (job.network().getContentVersion() != job.contentVersion()) {
            restartScan(job, null);
            return;
        }

        NetworkScanResult result = step.result();
        job.network().applyScanResult(result);
        scanJobs.remove(job.network());
        job.taskId = -1;
        logScan(job.network(), job.session(), job.network().getScanResult());
        for (Consumer<NetworkScanResult> callback : List.copyOf(job.callbacks())) {
            try {
                callback.accept(job.network().getScanResult());
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "NetworkStorage scan completion callback failed for " + job.network().getName(),
                        exception);
            }
        }
    }

    private void logScan(Network network,
                         NetworkContentScanner.ScanSession session,
                         NetworkScanResult result) {
        if (result.status() == NetworkScanStatus.COMPLETE) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "%s scan: %,d registered locations, %,d containers found, %,d items",
                    network.getName(),
                    session.registeredLocations(),
                    session.containersFound(),
                    result.totalItems()));
            return;
        }

        plugin.getLogger().warning(network.getName() + " scan " + result.status()
                + ": " + session.containersFound() + "/" + session.registeredLocations()
                + " containers found; item total is not authoritative.");
        for (String warning : result.warnings()) {
            plugin.getLogger().warning(warning);
        }
    }

    private void cancelScan(Network network) {
        ScanJob job = scanJobs.remove(network);
        if (job != null && job.taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(job.taskId);
            job.taskId = -1;
        }
    }

    private void detachNetwork(Network network) {
        cancelScan(network);
        attachedNetworks.remove(network);
        network.setContentChangeListener(null);
    }

    public void cancelScheduledScans() {
        requirePrimaryThread();
        scansCancelled = true;
        for (ScanJob job : new ArrayList<>(scanJobs.values())) {
            if (job.taskId != -1) {
                plugin.getServer().getScheduler().cancelTask(job.taskId);
            }
        }
        scanJobs.clear();
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

    private static final class ScanJob {
        private final Network network;
        private final NetworkContentScanner.ScanSession session;
        private final long contentVersion;
        private final List<Consumer<NetworkScanResult>> callbacks = new ArrayList<>();
        private int taskId = -1;

        private ScanJob(Network network, NetworkContentScanner.ScanSession session, long contentVersion) {
            this.network = network;
            this.session = session;
            this.contentVersion = contentVersion;
        }

        private Network network() {
            return network;
        }

        private NetworkContentScanner.ScanSession session() {
            return session;
        }

        private long contentVersion() {
            return contentVersion;
        }

        private List<Consumer<NetworkScanResult>> callbacks() {
            return callbacks;
        }
    }

    public void addChestToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        attachNetworkIfNeeded(network);
        network.addChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
        markDirty(network.getName());
    }

    public void addTerminalToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        attachNetworkIfNeeded(network);
        network.addTerminal(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
        markDirty(network.getName());
    }

    public void addSenderChestToNetwork(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        attachNetworkIfNeeded(network);
        network.addSenderChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
        markDirty(network.getName());
    }

    private void attachNetworkIfNeeded(Network network) {
        // A network loaded at startup is already attached.  This assignment is
        // harmless for newly-created networks and makes manager entry points
        // robust for tests and integrations that hand us a fresh Network.
        if (network != null && !attachedNetworks.contains(network)) {
            attachNetwork(network);
        }
    }

    public boolean removeTrackedLocation(Network network, Location location) {
        requirePrimaryThread();
        Location normalizedLocation = getNormalizedLocation(location);
        boolean changed = removeTrackedLocationExact(network, location);
        if (!normalizedLocation.equals(location)) {
            changed = removeTrackedLocationExact(network, normalizedLocation) || changed;
        }
        if (changed) {
            markDirty(network.getName());
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
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.create.global_mode"));
            return;
        }
        if (!isValidNetworkName(networkName)) {
            player.sendMessage(lang.getMessage("network.name.invalid"));
            return;
        }
        if (networks.containsKey(networkName)) {
            player.sendMessage(lang.getMessage("network.create.exists"));
            return;
        }
        Network network = new Network(networkName, player.getUniqueId(), plugin.getConfigManager());
        attachNetwork(network);
        networks.put(networkName, network);
        requestScan(network, true, null);
        markDirty(networkName);
        if (!selectedNetworks.containsKey(player.getUniqueId())) {
            selectedNetworks.put(player.getUniqueId(), networkName);
            playerStateDirty = true;
        }
        if (!saveNetworks()) {
            player.sendMessage("§cThe network was created in memory, but SQLite could not commit it. It will be retried automatically.");
            return;
        }
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
        if (networks.containsKey(newName)) {
            player.sendMessage(String.format(lang.getMessage("network.rename.exists"), newName));
            return;
        }
        Network network = networks.get(oldName);

        if (!network.getOwner().equals(player.getUniqueId()) && !plugin.getConfigManager().hasPrivilege(player, "networkstorage.admin")) {
             player.sendMessage(lang.getMessage("network.rename.permission"));
             return;
        }

        synchronized (renameLock) {
            network.setName(newName);
            networks.put(newName, network);
            networks.remove(oldName);
            dirtyNetworks.remove(oldName);
            markDirty(newName);
            boolean playerStateChanged = false;
            if (oldName.equals(selectedNetworks.get(network.getOwner()))) {
                selectedNetworks.put(network.getOwner(), newName);
                playerStateChanged = true;
            }
            for (Map.Entry<UUID, String> entry : selectedWirelessNetworks.entrySet()) {
                if (oldName.equals(entry.getValue())) {
                    entry.setValue(newName);
                    playerStateChanged = true;
                }
            }
            if (playerStateChanged) {
                playerStateDirty = true;
            }
        }
        if (!saveNetworks()) {
            player.sendMessage("§cThe rename could not be committed to SQLite. The change remains pending and will be retried automatically.");
            return;
        }
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
        return networks;
    }

    public Map<UUID, String> getSelectedWirelessNetworks() {
        return selectedWirelessNetworks;
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
            return location;
        }
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return location;
        }
        if (chestData.getType() == Chest.Type.RIGHT) {
            BlockFace direction = BlockUtils.findOtherChestHalf(Chest.Type.RIGHT, chestData.getFacing());
            if (direction != null) {
                return block.getRelative(direction).getLocation();
            }
        }
        return location;
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
        return getOwnedNetworks(player).stream()
                .filter(network -> network.getName().equalsIgnoreCase(networkName))
                .findFirst()
                .orElse(null);
    }

    public Network findAccessibleNetwork(Player player, String networkName) {
        return getAccessibleNetworks(player).stream()
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
        return savePlayerState();
    }

    public boolean selectWirelessNetwork(Player player, String networkName) {
        Network selectedNetwork = findAccessibleNetwork(player, networkName);
        if (selectedNetwork == null) {
            return false;
        }

        selectedWirelessNetworks.put(player.getUniqueId(), selectedNetwork.getName());
        return savePlayerState();
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
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        Network network = getPlayerNetwork(player);
        if (network == null) {
            String networkName = player.getName() + "'s Network";
            if (networks.containsKey(networkName) && !networks.get(networkName).getOwner().equals(player.getUniqueId())) {
                player.sendMessage(lang.getMessage("network.orcreate.exists"));
                return null;
            }
            network = new Network(networkName, player.getUniqueId(), plugin.getConfigManager());
            attachNetwork(network);
            networks.put(networkName, network);
            requestScan(network, true, null);
            markDirty(networkName);
        }
        return network;
    }

    public boolean isValidNetworkName(String networkName) {
        return StorageValues.isValidNetworkName(networkName);
    }

    public synchronized int purgeAllNetworksForSeasonReset() {
        List<Network> networksToPurge = new ArrayList<>(networks.values());
        if (networksToPurge.isEmpty()) {
            return 0;
        }

        for (Network network : networksToPurge) {
            archiveDeletedNetworkStats(network.getName(), network);
            clearNetworkChestContents(network);
            detachNetwork(network);
            resetNetworkInternal(network);
        }

        networks.clear();
        locationIndex.clear();
        selectedNetworks.clear();
        selectedWirelessNetworks.clear();
        dirtyNetworks.clear();
        storageDirty = true;
        playerStateDirty = true;

        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER, plugin.getConfigManager());
            attachNetwork(globalNetwork);
            requestScan(globalNetwork, true, null);
            markDirty(GLOBAL_NETWORK_NAME);
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
        }

        saveAllNetworks();
        return networksToPurge.size();
    }

    public synchronized boolean deleteNetwork(Player player, String networkName) {
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
        detachNetwork(network);
        resetNetworkInternal(network);
        networks.remove(networkName);
        dirtyNetworks.remove(networkName);
        storageDirty = true;
        playerStateDirty = true;

        for (Map.Entry<UUID, String> entry : selectedNetworks.entrySet()) {
            if (networkName.equals(entry.getValue())) {
                entry.setValue(null);
            }
        }
        selectedNetworks.values().removeIf(Objects::isNull);
        for (Map.Entry<UUID, String> entry : selectedWirelessNetworks.entrySet()) {
            if (networkName.equals(entry.getValue())) {
                entry.setValue(null);
            }
        }
        selectedWirelessNetworks.values().removeIf(Objects::isNull);

        if (!saveNetworks()) {
            player.sendMessage("§cThe deletion could not be committed to SQLite. The change remains pending and will be retried automatically.");
            return false;
        }
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
        resetNetworkInternal(network);
        markDirty(network.getName());
        return saveNetworks();
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
