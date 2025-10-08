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

import java.util.List;

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

        // Initialize managers
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);

        // Initialize listeners that need to be accessed
        this.chestInteractListener = new ChestInteractListener(this);
        this.searchManager = new SearchManager(this);

        // Register commands and tab completer
        StorageCommand storageCommand = new StorageCommand(this);
        getCommand("storage").setExecutor(storageCommand);
        getCommand("storage").setTabCompleter(storageCommand);
        getCommand("network").setExecutor(new NetworkCommand(this));
        getCommand("networkstorage").setExecutor(new NetworkStorageAdminCommand(this));

        // Register event listeners
        getServer().getPluginManager().registerEvents(chestInteractListener, this);
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getPluginManager().registerEvents(new AutoInsertListener(this), this);
        getServer().getPluginManager().registerEvents(new WirelessTerminalListener(this), this);

        // Register recipes
        registerRecipes();

        // Schedule repeating tasks
        startSenderChestTask();

        getLogger().info("NetworkStorage Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (networkManager != null) {
            networkManager.saveNetworks();
        }
        getLogger().info("NetworkStorage Plugin has been disabled!");
    }

    public void reload() {
        // Save all data first
        networkManager.saveNetworks();

        // Reload managers
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(this, configManager.getLanguage());
        networkManager = new NetworkManager(this);
    }

    private void registerRecipes() {
        NamespacedKey key = new NamespacedKey(this, "wireless_terminal");
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
        int interval = configManager.getSenderChestTransferInterval() * 20; // Convert seconds to ticks
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Network network : networkManager.getAllNetworks()) {
                for (Location senderLoc : network.getSenderChestLocations()) {
                    if (senderLoc.getBlock().getState() instanceof Chest) {
                        Chest senderChest = (Chest) senderLoc.getBlock().getState();
                        Inventory senderInv = senderChest.getInventory();
                        for (int i = 0; i < senderInv.getSize(); i++) {
                            ItemStack item = senderInv.getItem(i);
                            if (item != null && item.getType() != Material.AIR) {
                                ItemStack remaining = network.addToNetwork(item.clone());
                                if (remaining == null) {
                                    senderInv.setItem(i, null);
                                } else {
                                    item.setAmount(remaining.getAmount());
                                }
                            }
                        }
                    }
                }
            }
        }, 0L, interval);
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
}
