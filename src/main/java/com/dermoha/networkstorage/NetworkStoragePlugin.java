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
import com.dermoha.networkstorage.managers.SearchManager;
import com.dermoha.networkstorage.storage.Network;
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
    private ChestInteractListener chestInteractListener;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);

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

        registerRecipes();

        startSenderChestTask();
        startAutoSaveTask();
        startCacheResyncTask();

        getLogger().info("NetworkStorage Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (networkManager != null) {
            networkManager.saveNetworks();
        }
        getLogger().info("NetworkStorage Plugin has been disabled!");
    }

    public void reload() {
        networkManager.saveNetworks();
        Bukkit.getScheduler().cancelTasks(this);

        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);

        startSenderChestTask();
        startAutoSaveTask();
        startCacheResyncTask();
    }

    private void registerRecipes() {
        NamespacedKey key = new NamespacedKey(this, "wireless_terminal");
        ShapedRecipe recipe = new ShapedRecipe(key, WirelessTerminalListener.createWirelessTerminal(this));

        List<String> shape = getConfig().getStringList("wireless-terminal-recipe.shape");
        recipe.shape(shape.isEmpty() ? new String[]{"CCC", "CSC", "CDC"} : shape.toArray(new String[0]));

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
        if (interval <= 0) return;

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                Set<Location> senderChestLocations = network.getSenderChestLocations();
                Iterator<Location> iterator = senderChestLocations.iterator();
                while (iterator.hasNext()) {
                    Location senderLoc = iterator.next();
                    if (senderLoc.getWorld() == null || !senderLoc.getWorld().isChunkLoaded(senderLoc.getBlockX() >> 4, senderLoc.getBlockZ() >> 4)) {
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
                        getLogger().info("Pruned non-chest block at " + senderLoc + " from a network because it was no longer a chest.");
                    }
                }
            }
        }, 100L, interval);
    }

    private void startAutoSaveTask() {
        long interval = configManager.getAutoSaveInterval() * 60 * 20L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                getLogger().info("Auto-saving network data...");
                networkManager.saveNetworks();
                getLogger().info("Auto-save complete.");
            }, interval, interval);
        }
    }

    private void startCacheResyncTask() {
        long interval = configManager.getCacheResyncInterval() * 60 * 20L;
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                getLogger().info("Starting periodic cache resynchronization...");
                for (Network network : networkManager.getAllNetworks()) {
                    network.rebuildCache();
                }
                getLogger().info("Cache resynchronization complete.");
            }, interval, interval);
        }
    }

    public static NetworkStoragePlugin getInstance() { return instance; }
    public NetworkManager getNetworkManager() { return networkManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public SearchManager getSearchManager() { return searchManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public ChestInteractListener getChestInteractListener() { return chestInteractListener; }
}
