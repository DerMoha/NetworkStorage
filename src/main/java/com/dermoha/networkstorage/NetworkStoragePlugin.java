package com.dermoha.networkstorage;

import org.bukkit.plugin.java.JavaPlugin;
import com.dermoha.networkstorage.commands.StorageCommand;
import com.dermoha.networkstorage.listeners.ChestInteractListener;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.AutoInsertListener;
import com.dermoha.networkstorage.managers.NetworkManager;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.SearchManager;
import com.dermoha.networkstorage.managers.LanguageManager;

public class NetworkStoragePlugin extends JavaPlugin {

    private static NetworkStoragePlugin instance;
    private NetworkManager networkManager;
    private ConfigManager configManager;
    private SearchManager searchManager;
    private LanguageManager languageManager;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize managers
        configManager = new ConfigManager(this);
        languageManager = new LanguageManager(getConfig());
        networkManager = new NetworkManager(this);
        searchManager = new SearchManager(this);

        // Register commands
        getCommand("storage").setExecutor(new StorageCommand(this));

        // Register event listeners
        getServer().getPluginManager().registerEvents(new ChestInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getPluginManager().registerEvents(new AutoInsertListener(this), this);

        // Save default config
        saveDefaultConfig();

        getLogger().info("NetworkStorage Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (networkManager != null) {
            networkManager.saveNetworks();
        }
        getLogger().info("NetworkStorage Plugin has been disabled!");
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
}