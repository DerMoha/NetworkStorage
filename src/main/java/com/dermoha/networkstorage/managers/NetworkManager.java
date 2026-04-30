package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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
    private static final String GLOBAL_NETWORK_NAME = "Global";
    private static final UUID GLOBAL_NETWORK_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final Pattern SAFE_NETWORK_NAME = Pattern.compile("^[A-Za-z0-9 _'-]{1,32}$");

    public NetworkManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.networksFile = new File(plugin.getDataFolder(), "networks.yml");
        this.playerStateFile = new File(plugin.getDataFolder(), "player-state.yml");
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
                        UUID owner = UUID.fromString(netSection.getString("owner"));
                        Network network = new Network(networkName, owner);

                        List<Map<?, ?>> chestLocationsMaps = netSection.getMapList("chests");
                        for (Map<?, ?> locMap : chestLocationsMaps) {
                            network.addChest(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<Map<?, ?>> terminalLocationsMaps = netSection.getMapList("terminals");
                        for (Map<?, ?> locMap : terminalLocationsMaps) {
                            network.addTerminal(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<Map<?, ?>> senderChestLocationsMaps = netSection.getMapList("sender-chests");
                        for (Map<?, ?> locMap : senderChestLocationsMaps) {
                            network.addSenderChest(Location.deserialize((Map<String, Object>) locMap));
                        }

                        List<String> trustedUuids = netSection.getStringList("trusted");
                        for (String trustedUuid : trustedUuids) {
                            try {
                                network.addTrustedPlayer(UUID.fromString(trustedUuid));
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Skipping invalid trusted UUID '" + trustedUuid + "' in network '" + networkName + "'.");
                            }
                        }

                        // Load player stats
                        ConfigurationSection statsSection = netSection.getConfigurationSection("stats");
                        if (statsSection != null) {
                            for (String uuidString : statsSection.getKeys(false)) {
                                UUID playerUUID = UUID.fromString(uuidString);
                                String name = statsSection.getString(uuidString + ".name");
                                long deposited = statsSection.getLong(uuidString + ".deposited");
                                long withdrawn = statsSection.getLong(uuidString + ".withdrawn");
                                PlayerStat stat = new PlayerStat(playerUUID, name, deposited, withdrawn);
                                network.getPlayerStats().put(playerUUID, stat);
                            }
                        }

                        network.setDirty(false);
                        networks.put(networkName, network);
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
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER);
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
            globalNetwork.setDirty(true);
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
            e.printStackTrace();
        }
    }

    public void saveNetworks() {
        boolean hasDirtyNetworks = networks.values().stream().anyMatch(Network::isDirty);
        if (!hasDirtyNetworks) {
            return;
        }
        saveAllNetworksToDisk();
    }

    private void saveAllNetworksToDisk() {
        FileConfiguration newConfig = new YamlConfiguration();
        List<Network> savedNetworks = new ArrayList<>(networks.values());
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

            for (PlayerStat stat : network.getPlayerStats().values()) {
                String statPath = path + ".stats." + stat.getPlayerUUID().toString();
                newConfig.set(statPath + ".name", stat.getPlayerName());
                newConfig.set(statPath + ".deposited", stat.getItemsDeposited());
                newConfig.set(statPath + ".withdrawn", stat.getItemsWithdrawn());
            }
        }

        try {
            Path tempFile = networksFile.toPath().resolveSibling(networksFile.getName() + ".tmp");
            newConfig.save(tempFile.toFile());

            try {
                Files.move(tempFile, networksFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, networksFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            for (Network network : savedNetworks) {
                network.setDirty(false);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save networks to " + networksFile);
            e.printStackTrace();
        }
    }

    public void saveAllNetworks() {
        saveAllNetworksToDisk();
        savePlayerState();
    }

    public void addToLocationIndex(Location loc, Network network) {
        locationIndex.put(loc, network);
    }

    public void removeFromLocationIndex(Location loc) {
        locationIndex.remove(loc);
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
        Network network = new Network(networkName, player.getUniqueId());
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

    private Location getNormalizedLocation(Location location) {
        if (location.getBlock().getState() instanceof Chest chest
                && chest.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest doubleChest
                && doubleChest.getLeftSide() instanceof Chest leftChest) {
            return leftChest.getLocation();
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
            network = new Network(networkName, player.getUniqueId());
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
            clearNetworkChestContents(network);
            resetNetworkInternal(network);
        }

        networks.clear();
        locationIndex.clear();
        selectedNetworks.clear();
        selectedWirelessNetworks.clear();

        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            Network globalNetwork = new Network(GLOBAL_NETWORK_NAME, GLOBAL_NETWORK_OWNER);
            globalNetwork.setDirty(true);
            networks.put(GLOBAL_NETWORK_NAME, globalNetwork);
        }

        saveAllNetworks();
        return networksToPurge.size();
    }

    private void clearNetworkChestContents(Network network) {
        for (Location location : network.getChestLocations()) {
            if (!(location.getBlock().getState() instanceof Chest chest)) {
                continue;
            }
            chest.getInventory().clear();
            chest.update();
        }
    }

    public void resetNetwork(Network network) {
        resetNetworkInternal(network);
        saveNetworks();
    }

    private void resetNetworkInternal(Network network) {
        for (Location location : network.getChestLocations()) {
            network.removeChest(location);
            removeFromLocationIndex(location);
        }
        for (Location location : network.getTerminalLocations()) {
            network.removeTerminal(location);
            removeFromLocationIndex(location);
        }
        for (Location location : network.getSenderChestLocations()) {
            network.removeSenderChest(location);
            removeFromLocationIndex(location);
        }
    }
}
