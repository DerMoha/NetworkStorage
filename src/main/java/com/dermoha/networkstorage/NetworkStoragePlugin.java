package com.dermoha.networkstorage;

import com.dermoha.networkstorage.commands.NetworkCommand;
import com.dermoha.networkstorage.commands.NetworkStorageAdminCommand;
import com.dermoha.networkstorage.commands.StorageCommand;
import com.dermoha.networkstorage.gui.NetworkSelectGUI;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.gui.WirelessNetworkSelectGUI;
import com.dermoha.networkstorage.listeners.ChestInteractListener;
import com.dermoha.networkstorage.listeners.MiningTaxListener;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.managers.SearchManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.List;
import java.util.Set;
import java.util.Iterator;

public class NetworkStoragePlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 28228;

    private static NetworkStoragePlugin instance;
    private NetworkManager networkManager;
    private ConfigManager configManager;
    private SearchManager searchManager;
    private LanguageManager languageManager;
    private ChestInteractListener chestInteractListener;
    private MiningTaxListener miningTaxListener;
    private WandListener wandListener;
    private WirelessTerminalListener wirelessTerminalListener;
    private StorageCommand storageCommand;
    private NetworkCommand networkCommand;
    private NetworkStorageAdminCommand adminCommand;
    private int senderChestTaskId = -1;
    private int autoSaveTaskId = -1;
    private static final String WIRELESS_RECIPE_KEY = "wireless_terminal";

    @Override
    public void onEnable() {
        instance = this;
        createManagers();
        initializeMetrics();
        registerCommands();
        registerListeners();
        registerRecipes();
        startTasks();

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
        networkManager = new NetworkManager(this);
    }

    private void initializeMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("network_mode", () -> configManager.getNetworkMode().name().toLowerCase()));
        metrics.addCustomChart(new SingleLineChart("tracked_chests", this::getTrackedChestCount));
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

    private int getStoredItemCount() {
        long storedItemCount = 0;
        for (Network network : networkManager.getAllNetworks()) {
            for (int amount : network.getNetworkItems().values()) {
                storedItemCount += amount;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, storedItemCount);
    }

    private void registerCommands() {
        storageCommand = new StorageCommand(this);
        networkCommand = new NetworkCommand(this);
        adminCommand = new NetworkStorageAdminCommand(this);

        getCommand("storage").setExecutor(storageCommand);
        getCommand("storage").setTabCompleter(storageCommand);
        getCommand("network").setExecutor(networkCommand);
        getCommand("network").setTabCompleter(networkCommand);
        getCommand("networkstorage").setExecutor(adminCommand);
    }

    private void registerListeners() {
        chestInteractListener = new ChestInteractListener(this);
        miningTaxListener = new MiningTaxListener(this);
        wandListener = new WandListener(this);
        wirelessTerminalListener = new WirelessTerminalListener(this);
        searchManager = new SearchManager(this);

        getServer().getPluginManager().registerEvents(chestInteractListener, this);
        getServer().getPluginManager().registerEvents(miningTaxListener, this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        getServer().getPluginManager().registerEvents(wirelessTerminalListener, this);
    }

    private void startTasks() {
        startSenderChestTask();
        startAutoSaveTask();
    }

    private void unregisterRuntimeComponents() {
        if (searchManager != null) {
            searchManager.cleanup();
            HandlerList.unregisterAll(searchManager);
            searchManager = null;
        }
        if (chestInteractListener != null) {
            chestInteractListener.clearRuntimeState();
            HandlerList.unregisterAll(chestInteractListener);
            chestInteractListener = null;
        }
        if (miningTaxListener != null) {
            HandlerList.unregisterAll(miningTaxListener);
            miningTaxListener = null;
        }
        if (wandListener != null) {
            HandlerList.unregisterAll(wandListener);
            wandListener = null;
        }
        if (wirelessTerminalListener != null) {
            HandlerList.unregisterAll(wirelessTerminalListener);
            wirelessTerminalListener = null;
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
        ShapedRecipe recipe = new ShapedRecipe(key, WirelessTerminalListener.createWirelessTerminal(this));

        List<String> shape = getConfig().getStringList("wireless-terminal-recipe.shape");
        if (shape.isEmpty()) {
            recipe.shape("CCC", "CSC", "CDC");
        } else {
            recipe.shape(shape.toArray(new String[0]));
        }

        ConfigurationSection ingredients = getConfig().getConfigurationSection("wireless-terminal-recipe.ingredients");
        if (ingredients == null) {
            recipe.setIngredient('C', Material.COMPASS);
            recipe.setIngredient('S', Material.NETHER_STAR);
            recipe.setIngredient('D', Material.DIAMOND_BLOCK);
        } else {
            for (String keyChar : ingredients.getKeys(false)) {
                if (keyChar.length() == 1) {
                    String materialName = ingredients.getString(keyChar);
                    if (materialName != null) {
                        try {
                            Material ingredient = Material.valueOf(materialName.toUpperCase());
                            recipe.setIngredient(keyChar.charAt(0), ingredient);
                        } catch (IllegalArgumentException e) {
                            getLogger().warning("Invalid material '" + materialName + "' in wireless terminal recipe.");
                        }
                    }
                }
            }
        }

        getServer().addRecipe(recipe);
    }

    private void startSenderChestTask() {
        int interval = configManager.getSenderChestTransferInterval() * 20;
        senderChestTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                for (Location senderLoc : network.getSenderChestLocations()) {

                    if (!senderLoc.getWorld().isChunkLoaded(senderLoc.getBlockX() >> 4, senderLoc.getBlockZ() >> 4)) {
                        continue;
                    }

                    if (senderLoc.getBlock().getState() instanceof Chest) {
                        Chest senderChest = (Chest) senderLoc.getBlock().getState();
                        Inventory senderInv = senderChest.getInventory();
                        for (int i = 0; i < senderInv.getSize(); i++) {
                            ItemStack item = senderInv.getItem(i);
                            if (item != null && item.getType() != Material.AIR) {
                                ItemStack remaining = network.addToNetwork(item.clone());
                                if (remaining == null || remaining.getAmount() == 0) {
                                    senderInv.setItem(i, null);
                                } else {
                                    item.setAmount(remaining.getAmount());
                                }
                            }
                        }
                    } else {
                        network.removeSenderChest(senderLoc);
                        networkManager.removeFromLocationIndex(senderLoc);
                        getLogger().info("Pruned non-chest block at " + senderLoc.toString() + " from a network because it was no longer a chest.");
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

    public static NetworkStoragePlugin getInstance() {
        return instance;
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public SearchManager getSearchManager() {
        return searchManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ChestInteractListener getChestInteractListener() {
        return chestInteractListener;
    }

    public WirelessTerminalListener getWirelessTerminalListener() {
        return wirelessTerminalListener;
    }
}
