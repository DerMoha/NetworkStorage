package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    
    private final NetworkStoragePlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    private void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        // Set default values if they don't exist
        setDefaults();
        plugin.saveConfig();
    }
    
    private void setDefaults() {
        if (!config.contains("max-chests-per-network")) {
            config.set("max-chests-per-network", 100);
        }
        
        if (!config.contains("max-terminals-per-network")) {
            config.set("max-terminals-per-network", 10);
        }
        
        if (!config.contains("allow-cross-world-networks")) {
            config.set("allow-cross-world-networks", false);
        }
        
        if (!config.contains("auto-save-interval-minutes")) {
            config.set("auto-save-interval-minutes", 5);
        }
        
        if (!config.contains("enable-permissions")) {
            config.set("enable-permissions", true);
        }
        
        if (!config.contains("messages.network-created")) {
            config.set("messages.network-created", "&aStorage network created!");
        }
        
        if (!config.contains("messages.chest-added")) {
            config.set("messages.chest-added", "&aChest added to network! ({count} total)");
        }
        
        if (!config.contains("messages.terminal-added")) {
            config.set("messages.terminal-added", "&bTerminal added to network! ({count} total)");
        }
        
        if (!config.contains("messages.no-permission")) {
            config.set("messages.no-permission", "&cYou don't have permission to do that!");
        }
    }
    
    public int getMaxChestsPerNetwork() {
        return config.getInt("max-chests-per-network", 100);
    }
    
    public int getMaxTerminalsPerNetwork() {
        return config.getInt("max-terminals-per-network", 10);
    }
    
    public boolean isPermissionsEnabled() {
        return config.getBoolean("enable-permissions", true);
    }
    
    public boolean allowCrossWorldNetworks() {
        return config.getBoolean("allow-cross-world-networks", false);
    }
    
    public int getAutoSaveInterval() {
        return config.getInt("auto-save-interval-minutes", 5);
    }
    
    public String getMessage(String key) {
        String message = config.getString("messages." + key, "&cMessage not found: " + key);
        return message.replace("&", "§");
    }
    
    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }
}