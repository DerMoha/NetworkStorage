package com.dermoha.networkstorage;

import com.dermoha.networkstorage.commands.NetworkCommand;
import com.dermoha.networkstorage.commands.StorageCommand;
import com.dermoha.networkstorage.listeners.ComparatorOutputListener;
import com.dermoha.networkstorage.listeners.HopperIntegrationListener;
import com.dermoha.networkstorage.listeners.InventoryInteractionListener;
import com.dermoha.networkstorage.listeners.NetworkContainerListener;
import com.dermoha.networkstorage.listeners.StorageToolInteractionListener;
import com.dermoha.networkstorage.listeners.UpdateJoinListener;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.managers.TerminalSessions;
import com.dermoha.networkstorage.storage.DefaultMovementEvents;
import com.dermoha.networkstorage.storage.MovementEvents;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.NetworkScanStatus;
import com.dermoha.networkstorage.storage.NetworkStorageProvider;
import com.dermoha.networkstorage.storage.StorageException;
import com.dermoha.networkstorage.storage.sqlite.SqliteBackupManager;
import com.dermoha.networkstorage.storage.sqlite.SqliteNetworkStorageProvider;
import com.dermoha.networkstorage.storage.YamlToSqliteMigrator;
import com.dermoha.networkstorage.util.NetworkStorageConstants;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.Map;
import java.io.File;
import java.util.function.ToIntFunction;

public class NetworkStoragePlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 28228;

    private final com.dermoha.networkstorage.runtime.WirelessRecipeRegistration recipes =
            new com.dermoha.networkstorage.runtime.WirelessRecipeRegistration(this);

    private NetworkManager networkManager;
    private ConfigManager configManager;
    private NetworkStorageProvider storageProvider;
    private TerminalSessions terminalSessions;
    private LanguageManager languageManager;
    private MovementEvents movementEvents;
    private NetworkContainerListener networkContainerListener;
    private InventoryInteractionListener inventoryInteractionListener;
    private StorageToolInteractionListener storageToolInteractionListener;
    private WandListener wandListener;
    private WirelessTerminalListener wirelessTerminalListener;
    private StorageCommand storageCommand;
    private NetworkCommand networkCommand;
    private com.dermoha.networkstorage.api.NetworkStorageService apiService;
    private HopperIntegrationListener hopperIntegrationListener;
    private ComparatorOutputListener comparatorOutputListener;
    private com.dermoha.networkstorage.integrations.PlaceholderAPIHook placeholderAPIHook;
    private com.dermoha.networkstorage.UpdateChecker updateChecker;
    private com.dermoha.networkstorage.listeners.UpdateJoinListener updateJoinListener;
    private Metrics metrics;
    private SqliteBackupManager backupManager;
    private int senderChestTaskId = -1;
    private int autoSaveTaskId = -1;
    private int trustExpiryTaskId = -1;
    private int updateCheckTaskId = -1;
    private int storageBackupTaskId = -1;

    @Override
    public void onEnable() {
        try {
            startRuntime();
            initializeMetrics();
            getLogger().info("NetworkStorage Plugin has been enabled!");
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "NetworkStorage could not initialize", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopRuntime(true);
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        getLogger().info("NetworkStorage Plugin has been disabled!");
    }

    public boolean reload() {
        // Keep the current runtime, including backup scheduling, operational on failure.
        if (networkManager != null && !networkManager.flushPersistence()) {
            getLogger().severe("Reload aborted because the current SQLite snapshot could not be committed.");
            return false;
        }
        stopRuntime(false);
        try {
            startRuntime();
            return true;
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Reload failed; disabling NetworkStorage", e);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private void startRuntime() {
        createManagers();
        registerCommands();
        registerListeners();
        recipes.register();
        startTasks();
    }

    /** One teardown order for reload, partial startup failure and disable. */
    private void stopRuntime(boolean flush) {
        cancelScheduledTasks();
        if (updateChecker != null) updateChecker.close();
        closePluginInventories();
        unregisterRuntimeComponents();
        if (placeholderAPIHook != null) {
            placeholderAPIHook.close();
            placeholderAPIHook = null;
        }
        recipes.close();
        if (networkManager != null) {
            if (flush && !networkManager.flushPersistence()) {
                getLogger().severe("Final SQLite save failed. Uncommitted in-memory changes could not be persisted.");
            }
            networkManager.shutdownPersistence();
            networkManager = null;
        }
        if (storageProvider != null) {
            storageProvider.shutdown();
            storageProvider = null;
        }
        backupManager = null;
    }

    /** Applies editable settings without replacing active terminal/session objects. */
    public void applyRuntimeConfig() {
        configManager.reloadConfig();
        if (senderChestTaskId != -1) getServer().getScheduler().cancelTask(senderChestTaskId);
        if (autoSaveTaskId != -1) getServer().getScheduler().cancelTask(autoSaveTaskId);
        senderChestTaskId = -1;
        autoSaveTaskId = -1;
        startSenderChestTask();
        startAutoSaveTask();
    }

    private void createManagers() {
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        movementEvents = new DefaultMovementEvents(this, languageManager);

        File databaseFile = new File(getDataFolder(), "networks.db");
        SqliteNetworkStorageProvider.ensureDriver();
        SqliteNetworkStorageProvider.DatabaseLayout layout =
                SqliteNetworkStorageProvider.inspectLayout(databaseFile);
        if (layout == SqliteNetworkStorageProvider.DatabaseLayout.UNKNOWN) {
            throw new StorageException("The SQLite database layout is unknown or corrupt; refusing startup");
        }

        File yamlNetworks = new File(getDataFolder(), "networks.yml");
        File yamlPlayerState = new File(getDataFolder(), "player-state.yml");
        File migrationLock = new File(getDataFolder(), "migration.lock");
        if (migrationLock.exists()) {
            throw new StorageException("migration.lock is present; refusing to enable until the previous migration is inspected");
        }
        boolean hasYamlSource = hasFileData(yamlNetworks) || hasFileData(yamlPlayerState);
        YamlToSqliteMigrator migrator = new YamlToSqliteMigrator(this,
                yamlNetworks, yamlPlayerState, databaseFile);
        if (layout == SqliteNetworkStorageProvider.DatabaseLayout.PACKED_LEGACY) {
            if (hasYamlSource) {
                throw new StorageException("Legacy packed SQLite and live YAML sources coexist; refusing an ambiguous cutover");
            }
            getLogger().info("Detected legacy packed SQLite storage; converting it before enabling gameplay features...");
            migrator.migrateLegacyDatabase(databaseFile);
        } else if ((layout == SqliteNetworkStorageProvider.DatabaseLayout.ABSENT
                || layout == SqliteNetworkStorageProvider.DatabaseLayout.EMPTY) && hasYamlSource) {
            getLogger().info("Detected existing YAML data; migrating it before enabling gameplay features...");
            migrator.migrateYaml();
        }

        SqliteNetworkStorageProvider sqlite = new SqliteNetworkStorageProvider(this, databaseFile);
        sqlite.initialize();
        if (hasYamlSource && !"COMPLETE".equals(sqlite.getMetadata("migration_state"))) {
            sqlite.shutdown();
            throw new StorageException("YAML source files remain beside a SQLite database without completed migration metadata");
        }
        storageProvider = sqlite;
        backupManager = new SqliteBackupManager(this, sqlite);
        networkManager = new NetworkManager(this, sqlite);
        apiService = new com.dermoha.networkstorage.api.DefaultNetworkStorageService(this);
        placeholderAPIHook = new com.dermoha.networkstorage.integrations.PlaceholderAPIHook(this);
        updateChecker = new com.dermoha.networkstorage.UpdateChecker(this);
    }

    private boolean hasFileData(File file) {
        return file.isFile() && file.length() > 0;
    }

    private void initializeMetrics() {
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("network_mode", () -> configManager.getNetworkMode().name().toLowerCase(java.util.Locale.ROOT)));
        metrics.addCustomChart(new SingleLineChart("tracked_chests", this::getTrackedChestCount));
        metrics.addCustomChart(new AdvancedPie("tracked_chests_per_server", () -> Map.of(getTrackedChestCountBucket(getTrackedChestCount()), 1)));
        metrics.addCustomChart(new SingleLineChart("stored_items", this::getStoredItemCount));
        metrics.addCustomChart(new SimplePie("stored_items_scan_status", () -> networkManager == null ? "unavailable" : networkManager.getScanStatusForMetrics()));
        metrics.addCustomChart(new AdvancedPie("networks_per_server", () -> Map.of(networksBucket(networkManager.getNetworks().size()), 1)));
        metrics.addCustomChart(new SingleLineChart("wireless_terminal_users", () -> networkManager.getSelectedWirelessNetworks().size()));
        metrics.addCustomChart(new SingleLineChart("terminal_count", () -> sumN(n -> n.getTerminalLocations().size())));
        metrics.addCustomChart(new SingleLineChart("sender_chest_count", () -> sumN(n -> n.getSenderChestLocations().size())));
        metrics.addCustomChart(new SingleLineChart("trusted_players_total", () -> sumN(n -> n.getTrustedPlayers().size())));
        metrics.addCustomChart(new SimplePie("permissions_enabled", () -> configManager.isPermissionsEnabled() ? "true" : "false"));
        metrics.addCustomChart(new SimplePie("trust_system_enabled", () -> configManager.isTrustSystemEnabled() ? "true" : "false"));
        metrics.addCustomChart(new SimplePie("hopper_integration_enabled", () -> configManager.isHopperIntegrationEnabled() ? "true" : "false"));
        metrics.addCustomChart(new SimplePie("update_status", () -> updateChecker.getStatus()));
    }

    private int getTrackedChestCount() {
        int trackedChestCount = 0;
        for (Network network : networkManager.getAllNetworks()) {
            trackedChestCount += network.getChestLocations().size();
            trackedChestCount += network.getSenderChestLocations().size();
        }
        return trackedChestCount;
    }

    private int sumN(ToIntFunction<Network> f) {
        int total = 0;
        for (Network network : networkManager.getAllNetworks()) {
            total += f.applyAsInt(network);
        }
        return total;
    }

    private String getTrackedChestCountBucket(int n) {
        if (n == 0) {
            return "0";
        }
        if (n < 10) {
            return "1-9";
        }
        if (n < 25) {
            return "10-24";
        }
        if (n < 50) {
            return "25-49";
        }
        if (n < 100) {
            return "50-99";
        }
        return "100+";
    }

    private String networksBucket(int n) {
        if (n == 0) {
            return "0";
        }
        if (n == 1) {
            return "1";
        }
        if (n <= 5) {
            return "2-5";
        }
        if (n <= 15) {
            return "6-15";
        }
        if (n <= 50) {
            return "16-50";
        }
        return "50+";
    }

    private int getStoredItemCount() {
        return networkManager.getStoredItemCountForMetrics();
    }

    private void registerCommands() {
        storageCommand = new StorageCommand(this);
        networkCommand = new NetworkCommand(this);

        getCommand("storage").setExecutor(storageCommand);
        getCommand("storage").setTabCompleter(storageCommand);
        getCommand("network").setExecutor(networkCommand);
        getCommand("network").setTabCompleter(networkCommand);
        var unified = new com.dermoha.networkstorage.commands.UnifiedCommand(
                this, storageCommand, networkCommand, new com.dermoha.networkstorage.commands.AdminCommand(this));
        for (String name : new String[] {"storage", "network", "networkstorage"}) {
            getCommand(name).setExecutor(unified);
            getCommand(name).setTabCompleter(unified);
        }
    }

    private void registerListeners() {
        terminalSessions = new TerminalSessions(this);
        networkContainerListener = new NetworkContainerListener(this);
        inventoryInteractionListener = new InventoryInteractionListener(this);
        storageToolInteractionListener = new StorageToolInteractionListener(this);
        wandListener = new WandListener(this);
        wirelessTerminalListener = new WirelessTerminalListener(this);
        hopperIntegrationListener = new HopperIntegrationListener(this);
        comparatorOutputListener = new ComparatorOutputListener(this);
        updateJoinListener = new UpdateJoinListener(this, updateChecker);

        getServer().getPluginManager().registerEvents(terminalSessions, this);
        getServer().getPluginManager().registerEvents(storageToolInteractionListener, this);
        getServer().getPluginManager().registerEvents(networkContainerListener, this);
        getServer().getPluginManager().registerEvents(inventoryInteractionListener, this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        getServer().getPluginManager().registerEvents(wirelessTerminalListener, this);
        getServer().getPluginManager().registerEvents(hopperIntegrationListener, this);
        getServer().getPluginManager().registerEvents(comparatorOutputListener, this);
        getServer().getPluginManager().registerEvents(updateJoinListener, this);
    }

    private void startTasks() {
        startSenderChestTask();
        startAutoSaveTask();
        startTrustExpiryTask();
        startStorageBackupTask();
        startUpdateChecker();
    }

    private void startStorageBackupTask() {
        if (backupManager == null) {
            return;
        }
        int intervalHours = configManager.getStorageBackupIntervalHours();
        if (intervalHours <= 0) {
            getLogger().info("SQLite backup scheduler is disabled (storage.backup-interval-hours=0).");
            return;
        }
        long intervalTicks = (long) intervalHours * NetworkStorageConstants.TICKS_PER_HOUR;
        storageBackupTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                backupManager::createBackup,
                intervalTicks,
                intervalTicks
        ).getTaskId();
        getLogger().info("SQLite backups scheduled every " + intervalHours + " hour(s); retaining three verified copies.");
    }

    private void startUpdateChecker() {
        if (updateChecker == null) {
            return;
        }
        updateChecker.checkNow();
        if (updateCheckTaskId != -1) {
            return;
        }
        int intervalHours = configManager.getUpdateCheckIntervalHours();
        if (intervalHours <= 0) {
            getLogger().info("Update check periodic re-check is disabled (interval-hours=0).");
            return;
        }
        long intervalTicks = (long) intervalHours * NetworkStorageConstants.TICKS_PER_HOUR;
        updateCheckTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> updateChecker.checkNow(),
                intervalTicks,
                intervalTicks
        ).getTaskId();
        getLogger().info("Update check scheduled every " + intervalHours + " hour(s).");
    }

    private void unregisterRuntimeComponents() {
        if (terminalSessions != null) {
            terminalSessions.cleanup();
            HandlerList.unregisterAll(terminalSessions);
            terminalSessions = null;
        }
        if (networkContainerListener != null) {
            HandlerList.unregisterAll(networkContainerListener);
            networkContainerListener = null;
        }
        if (inventoryInteractionListener != null) {
            HandlerList.unregisterAll(inventoryInteractionListener);
            inventoryInteractionListener = null;
        }
        if (storageToolInteractionListener != null) {
            HandlerList.unregisterAll(storageToolInteractionListener);
            storageToolInteractionListener = null;
        }
        if (wandListener != null) {
            HandlerList.unregisterAll(wandListener);
            wandListener = null;
        }
        if (wirelessTerminalListener != null) {
            HandlerList.unregisterAll(wirelessTerminalListener);
            wirelessTerminalListener = null;
        }
        if (hopperIntegrationListener != null) {
            HandlerList.unregisterAll(hopperIntegrationListener);
            hopperIntegrationListener = null;
        }
        if (comparatorOutputListener != null) {
            HandlerList.unregisterAll(comparatorOutputListener);
            comparatorOutputListener = null;
        }
        if (updateJoinListener != null) {
            HandlerList.unregisterAll(updateJoinListener);
            updateJoinListener = null;
        }
    }

    private void closePluginInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory() == null) {
                continue;
            }

            Inventory topInventory = player.getOpenInventory().getTopInventory();
            if (topInventory == null) {
                continue;
            }

            Object holder = topInventory.getHolder();
            if (holder instanceof com.dermoha.networkstorage.gui.PluginInventory) {
                player.closeInventory();
            }
        }
    }

    private void cancelScheduledTasks() {
        if (senderChestTaskId != -1) {
            getServer().getScheduler().cancelTask(senderChestTaskId);
            senderChestTaskId = -1;
        }
        if (autoSaveTaskId != -1) {
            getServer().getScheduler().cancelTask(autoSaveTaskId);
            autoSaveTaskId = -1;
        }
        if (trustExpiryTaskId != -1) {
            getServer().getScheduler().cancelTask(trustExpiryTaskId);
            trustExpiryTaskId = -1;
        }
        if (updateCheckTaskId != -1) {
            getServer().getScheduler().cancelTask(updateCheckTaskId);
            updateCheckTaskId = -1;
        }
        cancelStorageBackupTask();
    }

    private void cancelStorageBackupTask() {
        if (storageBackupTaskId != -1) {
            getServer().getScheduler().cancelTask(storageBackupTaskId);
            storageBackupTaskId = -1;
        }
    }

    private void startSenderChestTask() {
        long interval = (long) configManager.getSenderChestTransferInterval() * NetworkStorageConstants.TICKS_PER_SECOND;
        var transfers = new com.dermoha.networkstorage.runtime.SenderChestTransfers(this, interval);
        senderChestTaskId = getServer().getScheduler().runTaskTimer(this, transfers::tick, 100L, 1L).getTaskId();
    }

    private void startAutoSaveTask() {
        long interval = (long) configManager.getAutoSaveInterval() * 60 * 20;
        if (interval > 0) {
            autoSaveTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
                getLogger().info("Auto-saving network data...");
                networkManager.queueFullSave();
                getLogger().info("Auto-save queued.");
            }, interval, interval).getTaskId();
        }
    }

    private void startTrustExpiryTask() {
        trustExpiryTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                network.pruneExpiredTrusts();
            }
        }, 1200L, 1200L).getTaskId();
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public TerminalSessions getTerminalSessions() {
        return terminalSessions;
    }

    public MovementEvents getMovementEvents() {
        return movementEvents;
    }

    public WirelessTerminalListener getWirelessTerminalListener() {
        return wirelessTerminalListener;
    }

    public com.dermoha.networkstorage.api.NetworkStorageService getApiService() {
        return apiService;
    }

    public com.dermoha.networkstorage.integrations.PlaceholderAPIHook getPlaceholderAPIHook() {
        return placeholderAPIHook;
    }

    public com.dermoha.networkstorage.UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}
