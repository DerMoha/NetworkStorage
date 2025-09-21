package com.yourserver.networkstorage;

import org.bukkit.plugin.java.JavaPlugin;
import com.yourserver.networkstorage.commands.StorageCommand;
import com.yourserver.networkstorage.listeners.ChestInteractListener;
import com.yourserver.networkstorage.listeners.WandListener;
import com.yourserver.networkstorage.listeners.AutoInsertListener;
import com.yourserver.networkstorage.managers.NetworkManager;
import com.yourserver.networkstorage.managers.ConfigManager;
import com.yourserver.networkstorage.managers.SearchManager;

public class NetworkStoragePlugin extends JavaPlugin {

    private static NetworkStoragePlugin instance;
    private NetworkManager networkManager;
    private ConfigManager configManager;
    private SearchManager searchManager;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize managers
        configManager = new ConfigManager(this);
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
}