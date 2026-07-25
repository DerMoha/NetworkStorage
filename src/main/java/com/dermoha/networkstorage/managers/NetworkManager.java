package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.BlockUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NetworkManager {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Map<String, Network> networks = new HashMap<>();
    private final Map<Location, Network> locationIndex = new HashMap<>();
    private final Map<UUID, String> selectedNetworks = new HashMap<>();
    private final Map<UUID, String> selectedWirelessNetworks = new HashMap<>();
    private final File networksFile;
    private final File playerStateFile;
    private final Object renameLock = new Object();
    private final Object diskWriteLock = new Object();
    private com.dermoha.networkstorage.storage.RollingBackupManager backupManager;
    private int networkSaveGeneration = 0;
    private int playerStateSaveGeneration = 0;
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final Pattern SAFE_NETWORK_NAME = Pattern.compile("^[A-Za-z0-9 _'-]{1,32}$");

    public NetworkManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.networksFile = new File(plugin.getDataFolder(), "networks.yml");
        this.playerStateFile = new File(plugin.getDataFolder(), "player-state.yml");
        this.backupManager = new com.dermoha.networkstorage.storage.RollingBackupManager(plugin);
        this.backupManager.initialize();
        ensureDataFileExists(networksFile, "networks.yml");
        ensureDataFileExists(playerStateFile, "player-state.yml");
        loadNetworks();
        loadPlayerState();
        pruneInvalidPlayerState();
    }

    private void ensureDataFileExists(File file, String fileName) {
        if (file.exists()) {
            return;
        }

        try {
            plugin.getDataFolder().mkdirs();
            file.createNewFile();
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create " + fileName + ": " + e.getMessage());
        }
    }

    private void loadNetworks() {
        boolean isGlobalMode = plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL;
        boolean globalNetworkLoaded = false;
        FileConfiguration networksConfig = YamlConfiguration.loadConfiguration(networksFile);
        ConfigurationSection networksSection = networksConfig.getConfigurationSection("networks");
        if (networksSection != null) {
            for (String networkName : networksSection.getKeys(false)) {
                ConfigurationSection netSection = networksSection.getConfigurationSection(networkName);
                if (netSection != null) {
                    try {
                        String ownerString = netSection.getString("owner");
                        if (ownerString == null) {
                            throw new IllegalArgumentException("missing owner UUID");
                        }
                        UUID owner = UUID.fromString(ownerString);
                        Network network = new Network(networkName, owner, plugin.getConfigManager(), plugin.getMovementEvents());

                        List<Map<?, ?>> chestLocationsMaps = netSection.getMapList("chests");
                        for (Map<?, ?> locMap : chestLocationsMaps) {
                            Location location = deserializeSavedLocation(locMap, networkName, "chest");
                            if (location != null) {
                                network.addChest(location);
                            }
                        }

                        List<Map<?, ?>> terminalLocationsMaps = netSection.getMapList("terminals");
                        for (Map<?, ?> locMap : terminalLocationsMaps) {
                            Location location = deserializeSavedLocation(locMap, networkName, "terminal");
                            if (location != null) {
                                network.addTerminal(location);
                            }
                        }

                        List<Map<?, ?>> senderChestLocationsMaps = netSection.getMapList("sender-chests");
                        for (Map<?, ?> locMap : senderChestLocationsMaps) {
                            Location location = deserializeSavedLocation(locMap, networkName, "sender chest");
                            if (location != null) {
                                network.addSenderChest(location);
                            }
                        }

                        List<String> trustedUuids = netSection.getStringList("trusted");
                        for (String trustedUuid : trustedUuids) {
                            try {
                                network.addTrustedPlayer(UUID.fromString(trustedUuid));
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Skipping invalid trusted UUID '" + trustedUuid + "' in network '" + networkName + "'.");
                            }
                        }

                        ConfigurationSection timedTrustSection = netSection.getConfigurationSection("trusted-expiry");
                        if (timedTrustSection != null) {
                            long now = System.currentTimeMillis();
                            for (String uuidString : timedTrustSection.getKeys(false)) {
                                try {
                                    UUID trustedId = UUID.fromString(uuidString);
                                    long expiresAt = timedTrustSection.getLong(uuidString);
                                    if (expiresAt > now) {
                                        network.addTrustedPlayerWithExpiry(trustedId, expiresAt);
                                    } else {
                                        plugin.getLogger().info("Skipping expired trust for " + uuidString + " in network '" + networkName + "'.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    plugin.getLogger().warning("Skipping invalid trusted-expiry UUID '" + uuidString + "' in network '" + networkName + "'.");
                                }
                            }
                        }

                        String description = netSection.getString("description", "");
                        if (description != null && !description.isEmpty()) {
                            network.setDescription(description);
                        }

                        // Load player stats
                        ConfigurationSection statsSection = netSection.getConfigurationSection("stats");
                        if (statsSection != null) {
                            for (String uuidString : statsSection.getKeys(false)) {
                                try {
                                    UUID playerUUID = UUID.fromString(uuidString);
                                    String name = statsSection.getString(uuidString + ".name");
                                    long deposited = Math.max(0L, statsSection.getLong(uuidString + ".deposited"));
                                    long withdrawn = Math.max(0L, statsSection.getLong(uuidString + ".withdrawn"));
                                    PlayerStat stat = new PlayerStat(playerUUID, name, deposited, withdrawn);
                                    network.getPlayerStats().put(playerUUID, stat);
                                } catch (IllegalArgumentException e) {
                                    plugin.getLogger().warning("Skipping invalid stats UUID '" + uuidString + "' in network '" + networkName + "'.");
                                }
                            }
                        }

                        network.setDirty(false);
                        networks.put(networkName, network);
                        recomputeNetworkTotal(network);
                        if (isGlobalMode && GLOBAL_NETWORK_NAME.equals(networkName)) {
                            globalNetworkLoaded = true;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not load network '" + networkName + "': " + e.getMessage());
                    }
                }
            }
        }

        if (isGlobalMode && !globalNetworkLoaded) {
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER, plugin.getConfigManager());
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
            globalNetwork.setDirty(true);
            plugin.getLogger().info("Created new global network in memory. It will be saved on next auto-save.");
        }

        rebuildLocationIndex();
    }

    private Location deserializeSavedLocation(Map<?, ?> locMap, String networkName, String locationType) {
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
                throw new IllegalArgumentException("invalid or unloaded world/coordinates");
            }
            String worldName = location.getWorld().getName();
            if (plugin.getServer().getWorld(worldName) == null) {
                throw new IllegalArgumentException("world '" + worldName + "' is not registered on this server");
            }
            if (Math.abs(location.getX()) > 3.0E7
                    || Math.abs(location.getZ()) > 3.0E7
                    || location.getY() < plugin.getServer().getWorld(worldName).getMinHeight() - 1024
                    || location.getY() > plugin.getServer().getWorld(worldName).getMaxHeight() + 1024) {
                throw new IllegalArgumentException("coordinates out of world bounds");
            }
            return location;
        } catch (Exception e) {
            plugin.getLogger().warning("Skipping invalid " + locationType + " location in network '" + networkName + "': " + e.getMessage());
            return null;
        }
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

    private void recomputeNetworkTotal(Network network) {
        long total = 0L;
        for (Location location : network.getChestLocations()) {
            if (!(location.getBlock().getState() instanceof Container container)) {
                continue;
            }
            if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            for (ItemStack item : container.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    total += item.getAmount();
                }
            }
        }
        network.setTotalStoredAmount(total);
    }

    private void loadPlayerState() {
        selectedNetworks.clear();
        selectedWirelessNetworks.clear();

        FileConfiguration playerStateConfig = YamlConfiguration.loadConfiguration(playerStateFile);
        ConfigurationSection playersSection = playerStateConfig.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String uuidString : playersSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(uuidString);
                String basePath = "players." + uuidString;
                String selectedOwnedNetwork = playerStateConfig.getString(basePath + ".selected-owned-network");
                String selectedWirelessNetwork = playerStateConfig.getString(basePath + ".selected-wireless-network");

                if (selectedOwnedNetwork != null && !selectedOwnedNetwork.isBlank()) {
                    selectedNetworks.put(playerId, selectedOwnedNetwork);
                }
                if (selectedWirelessNetwork != null && !selectedWirelessNetwork.isBlank()) {
                    selectedWirelessNetworks.put(playerId, selectedWirelessNetwork);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid player-state entry for UUID '" + uuidString + "'.");
            }
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
            savePlayerState();
        }
    }

    private void savePlayerState() {
        FileConfiguration newConfig = createPlayerStateSnapshot();
        int saveGeneration;
        synchronized (diskWriteLock) {
            saveGeneration = ++playerStateSaveGeneration;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (diskWriteLock) {
                if (saveGeneration != playerStateSaveGeneration) {
                    return;
                }
                savePlayerStateSnapshot(newConfig);
            }
        });
    }

    private FileConfiguration createPlayerStateSnapshot() {
        FileConfiguration newConfig = new YamlConfiguration();
        Set<UUID> playerIds = new HashSet<>();
        playerIds.addAll(selectedNetworks.keySet());
        playerIds.addAll(selectedWirelessNetworks.keySet());

        for (UUID playerId : playerIds) {
            String basePath = "players." + playerId;

            if (selectedNetworks.containsKey(playerId)) {
                newConfig.set(basePath + ".selected-owned-network", selectedNetworks.get(playerId));
            }
            if (selectedWirelessNetworks.containsKey(playerId)) {
                newConfig.set(basePath + ".selected-wireless-network", selectedWirelessNetworks.get(playerId));
            }
        }

        return newConfig;
    }

    private void savePlayerStateSnapshot(FileConfiguration newConfig) {
        try {
            Path tempFile = playerStateFile.toPath().resolveSibling(playerStateFile.getName() + ".tmp");
            newConfig.save(tempFile.toFile());

            try {
                Files.move(tempFile, playerStateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, playerStateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player state to " + playerStateFile);
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public void saveNetworks() {
        boolean hasDirtyNetworks = networks.values().stream().anyMatch(Network::isDirty);
        if (!hasDirtyNetworks) {
            return;
        }
        saveNetworksAsync();
    }

    private void saveNetworksAsync() {
        List<Network> savedNetworks = new ArrayList<>(networks.values());
        FileConfiguration newConfig = createNetworksSnapshot(savedNetworks);
        for (Network network : savedNetworks) {
            network.setDirty(false);
        }

        int saveGeneration;
        synchronized (diskWriteLock) {
            saveGeneration = ++networkSaveGeneration;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (diskWriteLock) {
                if (saveGeneration != networkSaveGeneration) {
                    return;
                }
                if (!saveNetworksSnapshot(newConfig)) {
                    for (Network network : savedNetworks) {
                        network.setDirty(true);
                    }
                }
            }
        });
    }

    private FileConfiguration createNetworksSnapshot(List<Network> savedNetworks) {
        FileConfiguration newConfig = new YamlConfiguration();
        for (Network network : savedNetworks) {
            String path = "networks." + network.getName();
            newConfig.set(path + ".owner", network.getOwner().toString());

            List<Map<String, Object>> serializedChests = new ArrayList<>();
            for (Location loc : network.getChestLocations()) {
                serializedChests.add(loc.serialize());
            }
            newConfig.set(path + ".chests", serializedChests);

            List<Map<String, Object>> serializedTerminals = new ArrayList<>();
            for (Location loc : network.getTerminalLocations()) {
                serializedTerminals.add(loc.serialize());
            }
            newConfig.set(path + ".terminals", serializedTerminals);

            List<Map<String, Object>> serializedSenderChests = new ArrayList<>();
            for (Location loc : network.getSenderChestLocations()) {
                serializedSenderChests.add(loc.serialize());
            }
            newConfig.set(path + ".sender-chests", serializedSenderChests);

            newConfig.set(path + ".trusted", network.getTrustedPlayers().stream().map(UUID::toString).collect(Collectors.toList()));

            Map<UUID, Long> timedTrusts = network.getTrustedPlayersWithExpiry();
            if (!timedTrusts.isEmpty()) {
                for (Map.Entry<UUID, Long> entry : timedTrusts.entrySet()) {
                    newConfig.set(path + ".trusted-expiry." + entry.getKey().toString(), entry.getValue());
                }
            }

            if (network.getDescription() != null && !network.getDescription().isEmpty()) {
                newConfig.set(path + ".description", network.getDescription());
            }

            for (PlayerStat stat : network.getPlayerStats().values()) {
                String statPath = path + ".stats." + stat.getPlayerUUID().toString();
                newConfig.set(statPath + ".name", stat.getPlayerName());
                newConfig.set(statPath + ".deposited", stat.getItemsDeposited());
                newConfig.set(statPath + ".withdrawn", stat.getItemsWithdrawn());
            }
        }

        return newConfig;
    }

    private boolean saveNetworksSnapshot(FileConfiguration newConfig) {
        try {
            Path tempFile = networksFile.toPath().resolveSibling(networksFile.getName() + ".tmp");
            newConfig.save(tempFile.toFile());

            try {
                Files.move(tempFile, networksFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, networksFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks to " + networksFile);
            plugin.getLogger().severe(e.getMessage());
            return false;
        }
    }

    public void saveAllNetworks() {
        List<Network> savedNetworks = new ArrayList<>(networks.values());
        FileConfiguration networksSnapshot = createNetworksSnapshot(savedNetworks);
        FileConfiguration playerStateSnapshot = createPlayerStateSnapshot();

        synchronized (diskWriteLock) {
            networkSaveGeneration++;
            playerStateSaveGeneration++;
            if (networksFile.length() > 0) {
                backupManager.createBackup(networksFile);
            }
            if (saveNetworksSnapshot(networksSnapshot)) {
                for (Network network : savedNetworks) {
                    network.setDirty(false);
                }
            }
            if (playerStateFile.exists() && playerStateFile.length() > 0) {
                backupManager.createBackup(playerStateFile);
            }
            savePlayerStateSnapshot(playerStateSnapshot);
        }
    }

    public void addChestToNetwork(Network network, Location location) {
        Location normalizedLocation = getNormalizedLocation(location);
        network.addChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
    }

    public void addTerminalToNetwork(Network network, Location location) {
        Location normalizedLocation = getNormalizedLocation(location);
        network.addTerminal(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
    }

    public void addSenderChestToNetwork(Network network, Location location) {
        Location normalizedLocation = getNormalizedLocation(location);
        network.addSenderChest(normalizedLocation);
        locationIndex.put(normalizedLocation, network);
    }

    public boolean removeTrackedLocation(Network network, Location location) {
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
        networks.put(networkName, network);
        network.setDirty(true);
        if (!selectedNetworks.containsKey(player.getUniqueId())) {
            selectedNetworks.put(player.getUniqueId(), networkName);
            savePlayerState();
        }
        saveNetworks();
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
                savePlayerState();
            }
        }
        saveNetworks();
        player.sendMessage(String.format(lang.getMessage("network.rename.success"), oldName, newName));
    }

    public Network getNetwork(String name) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            return networks.get(GLOBAL_NETWORK_NAME);
        }
        return networks.get(name);
    }

    public Collection<Network> getAllNetworks() {
        return networks.values();
    }

    public Map<String, Network> getNetworks() {
        return networks;
    }

    public Map<UUID, String> getSelectedWirelessNetworks() {
        return selectedWirelessNetworks;
    }

    public Network getNetworkByLocation(Location location) {
        Location normalizedLocation = getNormalizedLocation(location);

        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
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
        savePlayerState();
        return true;
    }

    public boolean selectWirelessNetwork(Player player, String networkName) {
        Network selectedNetwork = findAccessibleNetwork(player, networkName);
        if (selectedNetwork == null) {
            return false;
        }

        selectedWirelessNetworks.put(player.getUniqueId(), selectedNetwork.getName());
        savePlayerState();
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
        savePlayerState();
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
            savePlayerState();
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
            networks.put(networkName, network);
            network.setDirty(true);
        }
        return network;
    }

    public boolean isValidNetworkName(String networkName) {
        return networkName != null && SAFE_NETWORK_NAME.matcher(networkName).matches();
    }

    public synchronized int purgeAllNetworksForSeasonReset() {
        List<Network> networksToPurge = new ArrayList<>(networks.values());
        if (networksToPurge.isEmpty()) {
            return 0;
        }

        for (Network network : networksToPurge) {
            archiveDeletedNetworkStats(network.getName(), network);
            clearNetworkChestContents(network);
            resetNetworkInternal(network);
        }

        networks.clear();
        locationIndex.clear();
        selectedNetworks.clear();
        selectedWirelessNetworks.clear();

        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER, plugin.getConfigManager());
            globalNetwork.setDirty(true);
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

        archiveDeletedNetworkStats(networkName, network);
        clearNetworkChestContents(network);
        resetNetworkInternal(network);
        networks.remove(networkName);

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

        saveNetworks();
        savePlayerState();
        player.sendMessage(String.format(lang.getMessage("network.delete.success"), networkName));
        return true;
    }

    private void archiveDeletedNetworkStats(String networkName, Network network) {
        if (network.getPlayerStats().isEmpty()) {
            return;
        }
        File archiveFile = new File(plugin.getDataFolder(), "deleted-networks.log");
        try (java.io.FileWriter writer = new java.io.FileWriter(archiveFile, true)) {
            writer.write("[" + new java.util.Date() + "] DELETED NETWORK: " + networkName + "\n");
            for (java.util.Map.Entry<java.util.UUID, PlayerStat> entry : network.getPlayerStats().entrySet()) {
                PlayerStat stat = entry.getValue();
                writer.write("  - " + stat.getPlayerName() + " (" + entry.getKey() + "): deposited=" + stat.getItemsDeposited() + " withdrawn=" + stat.getItemsWithdrawn() + "\n");
            }
            writer.write("\n");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not write to deleted-networks log: " + e.getMessage());
        }
    }

    private void clearNetworkChestContents(Network network) {
        for (Location location : network.getChestLocations()) {
            if (!(location.getBlock().getState() instanceof Container container)) {
                continue;
            }
            container.getInventory().clear();
            container.update();
        }
        network.resetTotalStoredAmount();
    }

    public void resetNetwork(Network network) {
        resetNetworkInternal(network);
        saveNetworks();
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
    }
}
