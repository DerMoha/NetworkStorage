package com.dermoha.networkstorage;

import com.dermoha.networkstorage.commands.NetworkCommand;
import com.dermoha.networkstorage.commands.StorageCommand;
import com.dermoha.networkstorage.gui.NetworkSelectGUI;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.gui.WirelessNetworkSelectGUI;
import com.dermoha.networkstorage.listeners.HopperIntegrationListener;
import com.dermoha.networkstorage.listeners.InventoryInteractionListener;
import com.dermoha.networkstorage.listeners.NetworkContainerListener;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.managers.TerminalSessions;
import com.dermoha.networkstorage.storage.DefaultMovementEvents;
import com.dermoha.networkstorage.storage.MovementEvents;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.NetworkStorageConstants;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Iterator;

public class NetworkStoragePlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 28228;

    private NetworkManager networkManager;
    private ConfigManager configManager;
    private TerminalSessions terminalSessions;
    private LanguageManager languageManager;
    private MovementEvents movementEvents;
    private NetworkContainerListener networkContainerListener;
    private InventoryInteractionListener inventoryInteractionListener;
    private WandListener wandListener;
    private WirelessTerminalListener wirelessTerminalListener;
    private StorageCommand storageCommand;
    private NetworkCommand networkCommand;
    private com.dermoha.networkstorage.api.NetworkStorageService apiService;
    private HopperIntegrationListener hopperIntegrationListener;
    private int senderChestTaskId = -1;
    private int autoSaveTaskId = -1;
    private static final String WIRELESS_RECIPE_KEY = "wireless_terminal";
    private static final long BSTATS_STORED_ITEM_CACHE_TTL_MS = NetworkStorageConstants.BSTATS_STORED_ITEM_CACHE_TTL_MS;

    private volatile long cachedStoredItemCount = 0L;
    private volatile long cachedStoredItemCountAtMs = 0L;
    private final Object storedItemCountCacheLock = new Object();

    @Override
    public void onEnable() {
        createManagers();
        initializeMetrics();
        registerCommands();
        registerListeners();
        registerRecipes();
        startTasks();
        checkForUpdates();

        getLogger().info("NetworkStorage Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (networkManager != null) {
            networkManager.saveAllNetworks();
        }
        closePluginInventories();
        unregisterRuntimeComponents();
        cancelScheduledTasks();
        getLogger().info("NetworkStorage Plugin has been disabled!");
    }

    public void reload() {
        networkManager.saveAllNetworks();
        closePluginInventories();
        cancelScheduledTasks();
        unregisterRuntimeComponents();
        createManagers();
        registerCommands();
        registerListeners();
        registerRecipes();
        startTasks();
    }

    private void createManagers() {
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        movementEvents = new DefaultMovementEvents(this, languageManager);
        networkManager = new NetworkManager(this);
        apiService = new com.dermoha.networkstorage.api.DefaultNetworkStorageService(this);
    }

    private void initializeMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("network_mode", () -> configManager.getNetworkMode().name().toLowerCase()));
        metrics.addCustomChart(new SingleLineChart("tracked_chests", this::getTrackedChestCount));
        metrics.addCustomChart(new AdvancedPie("tracked_chests_per_server", this::getTrackedChestCountDistribution));
        metrics.addCustomChart(new SingleLineChart("stored_items", this::getStoredItemCount));
    }

    private int getTrackedChestCount() {
        int trackedChestCount = 0;
        for (Network network : networkManager.getAllNetworks()) {
            trackedChestCount += network.getChestLocations().size();
            trackedChestCount += network.getSenderChestLocations().size();
        }
        return trackedChestCount;
    }

    private Map<String, Integer> getTrackedChestCountDistribution() {
        return Map.of(getTrackedChestCountBucket(getTrackedChestCount()), 1);
    }

    private String getTrackedChestCountBucket(int trackedChestCount) {
        if (trackedChestCount == 0) {
            return "0";
        }
        if (trackedChestCount < 10) {
            return "1-9";
        }
        if (trackedChestCount < 25) {
            return "10-24";
        }
        if (trackedChestCount < 50) {
            return "25-49";
        }
        if (trackedChestCount < 100) {
            return "50-99";
        }
        if (trackedChestCount < 250) {
            return "100-249";
        }
        return "250+";
    }

    private int getStoredItemCount() {
        long now = System.currentTimeMillis();
        long cachedAt;
        long cached;
        synchronized (storedItemCountCacheLock) {
            cachedAt = cachedStoredItemCountAtMs;
            cached = cachedStoredItemCount;
        }
        if (cachedAt > 0 && (now - cachedAt) < BSTATS_STORED_ITEM_CACHE_TTL_MS) {
            return (int) Math.min(Integer.MAX_VALUE, cached);
        }
        return refreshStoredItemCountCache();
    }

    private int refreshStoredItemCountCache() {
        long total = 0L;
        for (Network network : networkManager.getAllNetworks()) {
            total += network.getTotalStoredAmount();
        }
        synchronized (storedItemCountCacheLock) {
            cachedStoredItemCount = total;
            cachedStoredItemCountAtMs = System.currentTimeMillis();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private void registerCommands() {
        storageCommand = new StorageCommand(this);
        networkCommand = new NetworkCommand(this);

        getCommand("storage").setExecutor(storageCommand);
        getCommand("storage").setTabCompleter(storageCommand);
        getCommand("network").setExecutor(networkCommand);
        getCommand("network").setTabCompleter(networkCommand);
        PluginCommand networkStorageCommand = getCommand("networkstorage");
        networkStorageCommand.setExecutor((sender, command, label, args) -> {
            if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
                sender.sendMessage("§cUsage: /networkstorage reload");
                return true;
            }

            if (!configManager.hasPermission(sender, "networkstorage.admin")) {
                sender.sendMessage(languageManager.getMessage("no_permission_reload"));
                return true;
            }

            sender.sendMessage(languageManager.getMessage("reload.start"));
            reload();
            sender.sendMessage(languageManager.getMessage("reload.success"));
            return true;
        });
    }

    private void registerListeners() {
        terminalSessions = new TerminalSessions(this);
        networkContainerListener = new NetworkContainerListener(this);
        inventoryInteractionListener = new InventoryInteractionListener(this);
        wandListener = new WandListener(this);
        wirelessTerminalListener = new WirelessTerminalListener(this);
        hopperIntegrationListener = new HopperIntegrationListener(this);

        getServer().getPluginManager().registerEvents(terminalSessions, this);
        getServer().getPluginManager().registerEvents(networkContainerListener, this);
        getServer().getPluginManager().registerEvents(inventoryInteractionListener, this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        getServer().getPluginManager().registerEvents(wirelessTerminalListener, this);
        getServer().getPluginManager().registerEvents(hopperIntegrationListener, this);
    }

    private void startTasks() {
        startSenderChestTask();
        startAutoSaveTask();
    }

    private void checkForUpdates() {
        new UpdateChecker(this).checkForUpdates();
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
            if (holder instanceof TerminalGUI
                    || holder instanceof StatsGUI
                    || holder instanceof NetworkSelectGUI
                    || holder instanceof WirelessNetworkSelectGUI) {
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
    }

    private void registerRecipes() {
        NamespacedKey key = new NamespacedKey(this, WIRELESS_RECIPE_KEY);
        getServer().removeRecipe(key);
        if (!tryRegisterWirelessRecipe(key, getConfiguredWirelessRecipeShape(), true)) {
            getLogger().warning("Falling back to the default wireless terminal recipe.");
            getServer().removeRecipe(key);
            tryRegisterWirelessRecipe(key, getDefaultWirelessRecipeShape(), false);
        }
    }

    private boolean tryRegisterWirelessRecipe(NamespacedKey key, String[] shape, boolean useConfiguredIngredients) {
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, WirelessTerminalListener.createWirelessTerminal(this));
            recipe.shape(shape);
            if (useConfiguredIngredients) {
                if (!applyConfiguredWirelessRecipeIngredients(recipe, shape)) {
                    return false;
                }
            } else {
                applyDefaultWirelessRecipeIngredients(recipe);
            }
            if (!getServer().addRecipe(recipe)) {
                getLogger().warning("Wireless terminal recipe could not be registered.");
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid wireless terminal recipe config: " + e.getMessage());
            return false;
        }
    }

    private String[] getConfiguredWirelessRecipeShape() {
        List<String> shape = getConfig().getStringList("wireless-terminal-recipe.shape");
        if (shape.isEmpty()) {
            return getDefaultWirelessRecipeShape();
        }
        if (!isValidRecipeShape(shape)) {
            getLogger().warning("Invalid wireless terminal recipe shape; using default shape.");
            return getDefaultWirelessRecipeShape();
        }
        return shape.toArray(new String[0]);
    }

    private boolean isValidRecipeShape(List<String> shape) {
        if (shape.isEmpty() || shape.size() > 3) {
            return false;
        }
        int width = shape.get(0).length();
        if (width == 0 || width > 3) {
            return false;
        }
        boolean hasIngredientSlot = false;
        for (String row : shape) {
            if (row == null || row.length() != width) {
                return false;
            }
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) != ' ') {
                    hasIngredientSlot = true;
                }
            }
        }
        return hasIngredientSlot;
    }

    private boolean applyConfiguredWirelessRecipeIngredients(ShapedRecipe recipe, String[] shape) {
        Set<Character> requiredIngredients = getRequiredRecipeIngredients(shape);
        Set<Character> configuredIngredients = new java.util.HashSet<>();
        ConfigurationSection ingredients = getConfig().getConfigurationSection("wireless-terminal-recipe.ingredients");
        if (ingredients == null) {
            applyDefaultWirelessRecipeIngredients(recipe);
            configuredIngredients.add('C');
            configuredIngredients.add('S');
            configuredIngredients.add('D');
            return hasAllRequiredRecipeIngredients(requiredIngredients, configuredIngredients);
        }

        for (String keyChar : ingredients.getKeys(false)) {
            if (keyChar.length() != 1) {
                getLogger().warning("Ignoring invalid wireless recipe ingredient key '" + keyChar + "'.");
                continue;
            }
            char ingredientKey = keyChar.charAt(0);
            if (!requiredIngredients.contains(ingredientKey)) {
                getLogger().warning("Ignoring unused wireless recipe ingredient key '" + keyChar + "'.");
                continue;
            }

            String materialName = ingredients.getString(keyChar);
            Material ingredient = materialName == null ? null : Material.matchMaterial(materialName);
            if (ingredient == null || ingredient == Material.AIR || !ingredient.isItem()) {
                getLogger().warning("Invalid material '" + materialName + "' in wireless terminal recipe.");
                continue;
            }
            recipe.setIngredient(ingredientKey, ingredient);
            configuredIngredients.add(ingredientKey);
        }

        return hasAllRequiredRecipeIngredients(requiredIngredients, configuredIngredients);
    }

    private Set<Character> getRequiredRecipeIngredients(String[] shape) {
        Set<Character> requiredIngredients = new java.util.HashSet<>();
        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                char ingredientKey = row.charAt(i);
                if (ingredientKey != ' ') {
                    requiredIngredients.add(ingredientKey);
                }
            }
        }
        return requiredIngredients;
    }

    private boolean hasAllRequiredRecipeIngredients(Set<Character> requiredIngredients, Set<Character> configuredIngredients) {
        for (char ingredientKey : requiredIngredients) {
            if (!configuredIngredients.contains(ingredientKey)) {
                getLogger().warning("Missing wireless recipe ingredient for key '" + ingredientKey + "'.");
                return false;
            }
        }
        return true;
    }

    private void applyDefaultWirelessRecipeIngredients(ShapedRecipe recipe) {
        recipe.setIngredient('C', Material.COMPASS);
        recipe.setIngredient('S', Material.NETHER_STAR);
        recipe.setIngredient('D', Material.DIAMOND_BLOCK);
    }

    private String[] getDefaultWirelessRecipeShape() {
        return new String[] {"CCC", "CSC", "CDC"};
    }

    private void startSenderChestTask() {
        int interval = configManager.getSenderChestTransferInterval() * 20;
        senderChestTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                for (Location senderLoc : network.getSenderChestLocations()) {

                    if (!senderLoc.getWorld().isChunkLoaded(senderLoc.getBlockX() >> 4, senderLoc.getBlockZ() >> 4)) {
                        continue;
                    }

                    if (senderLoc.getBlock().getState() instanceof org.bukkit.inventory.InventoryHolder holder) {
                        Inventory senderInv = holder.getInventory();
                        for (int i = 0; i < senderInv.getSize(); i++) {
                            network.getMovement().absorbFromInventory(senderInv, i);
                        }
                    } else {
                        networkManager.removeTrackedLocation(network, senderLoc);
                        getLogger().info("Pruned non-inventory block at " + senderLoc.toString() + " from a network because it was no longer a container.");
                    }
                }
            }
        }, 100L, interval).getTaskId();
    }

    private void startAutoSaveTask() {
        int interval = configManager.getAutoSaveInterval() * 60 * 20;
        if (interval > 0) {
            autoSaveTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
                getLogger().info("Auto-saving network data...");
                networkManager.saveAllNetworks();
                getLogger().info("Auto-save complete.");
            }, interval, interval).getTaskId();
        }
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
}
