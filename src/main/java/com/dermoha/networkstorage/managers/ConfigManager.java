package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final NetworkStoragePlugin plugin;
    private FileConfiguration config;

    public enum NetworkMode {
        PLAYER,
        GLOBAL
    }

    public ConfigManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        setDefaults();
        plugin.saveConfig();
    }

    private void setDefaults() {
        config.addDefault("network-mode", "PLAYER");
        config.addDefault("max-chests-per-network", 100);
        config.addDefault("max-terminals-per-network", 100);
        config.addDefault("max-sender-chests-per-network", 100);
        config.addDefault("sender-chest-transfer-interval-seconds", 5);
        config.addDefault("auto-save-interval-minutes", 5);
        config.addDefault("cache-resync-interval-minutes", 10);
        config.addDefault("enable-permissions", true);
        config.addDefault("enable-trust-system", true);
        config.addDefault("language", "en");
        config.addDefault("wireless-terminal-durability", 100);
        config.options().copyDefaults(true);
    }

    public NetworkMode getNetworkMode() {
        try {
            return NetworkMode.valueOf(config.getString("network-mode", "PLAYER").toUpperCase());
        } catch (IllegalArgumentException e) {
            return NetworkMode.PLAYER;
        }
    }

    public int getMaxChestsPerNetwork() { return config.getInt("max-chests-per-network"); }
    public int getMaxTerminalsPerNetwork() { return config.getInt("max-terminals-per-network"); }
    public int getMaxSenderChestsPerNetwork() { return config.getInt("max-sender-chests-per-network"); }
    public int getSenderChestTransferInterval() { return config.getInt("sender-chest-transfer-interval-seconds"); }
    public int getWirelessTerminalDurability() { return config.getInt("wireless-terminal-durability"); }
    public boolean isPermissionsEnabled() { return config.getBoolean("enable-permissions"); }
    public boolean isTrustSystemEnabled() { return config.getBoolean("enable-trust-system"); }
    public int getAutoSaveInterval() { return config.getInt("auto-save-interval-minutes"); }
    public int getCacheResyncInterval() { return config.getInt("cache-resync-interval-minutes"); }
    public String getLanguage() { return config.getString("language"); }

    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }
}
