package com.dermoha.networkstorage;

import com.dermoha.networkstorage.commands.NetworkCommand;
import com.dermoha.networkstorage.commands.NetworkStorageAdminCommand;
import com.dermoha.networkstorage.commands.StorageCommand;
import com.dermoha.networkstorage.listeners.AutoInsertListener;
import com.dermoha.networkstorage.listeners.ChestInteractListener;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.managers.ProtectionManager;
import com.dermoha.networkstorage.managers.SearchManager;
import com.dermoha.networkstorage.storage.Network;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class NetworkStoragePlugin extends JavaPlugin {

    private static NetworkStoragePlugin instance;
    private NetworkManager networkManager;
    private ConfigManager configManager;
    private SearchManager searchManager;
    private LanguageManager languageManager;
    private ProtectionManager protectionManager;
    private ChestInteractListener chestInteractListener;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);
        protectionManager = new ProtectionManager(this);

        this.chestInteractListener = new ChestInteractListener(this);
        this.searchManager = new SearchManager(this);

        StorageCommand storageCommand = new StorageCommand(this);
        getCommand("storage").setExecutor(storageCommand);
        getCommand("storage").setTabCompleter(storageCommand);
        getCommand("network").setExecutor(new NetworkCommand(this));
        getCommand("networkstorage").setExecutor(new NetworkStorageAdminCommand(this));

        getServer().getPluginManager().registerEvents(chestInteractListener, this);
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getPluginManager().registerEvents(new AutoInsertListener(this), this);
        getServer().getPluginManager().registerEvents(new WirelessTerminalListener(this), this);

        if (configManager.isWirelessTerminalsEnabled()) {
            registerRecipes();
        }

        startSenderChestTask();
        startAutoSaveTask();
        startCacheResyncTask();

        int pluginId = 28228; // Replace with your actual bStats plugin ID
        Metrics metrics = new Metrics(this, pluginId);

        metrics.addCustomChart(new SimplePie("network_mode", () -> configManager.getNetworkMode().name()));

        metrics.addCustomChart(new SimplePie("language", () -> configManager.getLanguage()));

        metrics.addCustomChart(new SimplePie("wireless_terminals_enabled",
                () -> configManager.isWirelessTerminalsEnabled() ? "Enabled" : "Disabled"));

        metrics.addCustomChart(new SimplePie("protection_check_enabled",
                () -> configManager.isProtectionCheckEnabled() ? "Enabled" : "Disabled"));

        metrics.addCustomChart(new SingleLineChart("total_networks", () -> networkManager.getAllNetworks().size()));

        metrics.addCustomChart(new SingleLineChart("total_chests", () -> {
            int totalChests = 0;
            for (Network network : networkManager.getAllNetworks()) {
                totalChests += network.getChestLocations().size();
            }
            return totalChests;
        }));

        metrics.addCustomChart(new SingleLineChart("total_terminals", () -> {
            int totalTerminals = 0;
            for (Network network : networkManager.getAllNetworks()) {
                totalTerminals += network.getTerminalLocations().size();
            }
            return totalTerminals;
        }));

        metrics.addCustomChart(new SingleLineChart("total_sender_chests", () -> {
            int totalSenderChests = 0;
            for (Network network : networkManager.getAllNetworks()) {
                totalSenderChests += network.getSenderChestLocations().size();
            }
            return totalSenderChests;
        }));

        metrics.addCustomChart(new SimplePie("average_chests_per_network", () -> {
            int totalNetworks = networkManager.getAllNetworks().size();
            if (totalNetworks == 0)
                return "0";

            int totalChests = 0;
            for (Network network : networkManager.getAllNetworks()) {
                totalChests += network.getChestLocations().size();
            }

            int average = totalChests / totalNetworks;

            if (average == 0)
                return "0";
            if (average <= 5)
                return "1-5";
            if (average <= 10)
                return "6-10";
            if (average <= 25)
                return "11-25";
            if (average <= 50)
                return "26-50";
            if (average <= 100)
                return "51-100";
            return "100+";
        }));
        getLogger().info("NetworkStorage Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (searchManager != null) {
            searchManager.cleanup();
        }
        if (networkManager != null) {
            getLogger().info("Saving network data before shutdown...");
            networkManager.saveNetworks();
            getLogger().info("Network data saved.");
        }
        getLogger().info("NetworkStorage Plugin has been disabled!");
    }

    public void reload() {
        if (networkManager != null) {
            networkManager.saveNetworks();
        }
        Bukkit.getScheduler().cancelTasks(this);

        NamespacedKey wirelessKey = new NamespacedKey(this, "wireless_terminal");
        Bukkit.removeRecipe(wirelessKey);

        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);

        if (configManager.isWirelessTerminalsEnabled()) {
            registerRecipes();
        }

        startSenderChestTask();
        startAutoSaveTask();
        startCacheResyncTask();
    }

    private void registerRecipes() {
        NamespacedKey key = new NamespacedKey(this, "wireless_terminal");
        ShapedRecipe recipe = new ShapedRecipe(key, WirelessTerminalListener.createWirelessTerminal(this));

        List<String> shape = getConfig().getStringList("wireless-terminal-recipe.shape");
        recipe.shape(shape.isEmpty() ? new String[] { "CCC", "CSC", "CDC" } : shape.toArray(new String[0]));

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
                            recipe.setIngredient(keyChar.charAt(0), Material.valueOf(materialName.toUpperCase()));
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
        long interval = configManager.getSenderChestTransferInterval() * 20L;
        if (interval <= 0)
            return;

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                Set<Location> senderChestLocations = network.getSenderChestLocations();
                Iterator<Location> iterator = senderChestLocations.iterator();
                while (iterator.hasNext()) {
                    Location senderLoc = iterator.next();
                    if (senderLoc.getWorld() == null || !senderLoc.getWorld().isChunkLoaded(senderLoc.getBlockX() >> 4,
                            senderLoc.getBlockZ() >> 4)) {
                        continue;
                    }

                    if (senderLoc.getBlock().getState() instanceof Chest) {
                        Chest senderChest = (Chest) senderLoc.getBlock().getState();
                        Inventory senderInv = senderChest.getInventory();
                        for (int i = 0; i < senderInv.getSize(); i++) {
                            ItemStack item = senderInv.getItem(i);
                            if (item != null && item.getType() != Material.AIR) {
                                ItemStack remaining = network.addToNetwork(item.clone());
                                senderInv.setItem(i, remaining);
                            }
                        }
                    } else {
                        iterator.remove();
                        getLogger().info("Pruned non-chest block at " + senderLoc
                                + " from a network because it was no longer a chest.");
                    }
                }
            }
        }, 100L, interval);
    }

    private void startAutoSaveTask() {
        long interval = configManager.getAutoSaveInterval() * 60 * 20L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                getLogger().info("[NetworkStorage] Auto-saving network data...");
                networkManager.saveNetworks();
                getLogger().info("[NetworkStorage] Auto-save complete.");
            }, interval, interval);
        }
    }

    private void startCacheResyncTask() {
        long interval = configManager.getCacheResyncInterval() * 60 * 20L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                getLogger().info("Starting periodic cache resynchronization...");
                for (Network network : networkManager.getAllNetworks()) {
                    network.rebuildCache();
                }
                getLogger().info("Cache resynchronization complete.");
            }, interval, interval);
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

    public ProtectionManager getProtectionManager() {
        return protectionManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public ChestInteractListener getChestInteractListener() {
        return chestInteractListener;
    }
}
