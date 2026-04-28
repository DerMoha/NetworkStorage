package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.command.CommandSender;
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
        config.addDefault("enable-permissions", true);
        config.addDefault("enable-trust-system", true);
        config.addDefault("language", "en");
        config.addDefault("mining-tax.enabled", false);
        config.addDefault("mining-tax.rate", 10.0);
        config.addDefault("wireless-terminal-durability", 100);
        config.addDefault("custom-model-data.wireless-terminal", 10001);
        config.addDefault("custom-model-data.wand", 10002);
        config.addDefault("custom-model-data.gui.terminal.prev-page", 10101);
        config.addDefault("custom-model-data.gui.terminal.next-page", 10102);
        config.addDefault("custom-model-data.gui.terminal.search", 10103);
        config.addDefault("custom-model-data.gui.terminal.sort", 10104);
        config.addDefault("custom-model-data.gui.terminal.info", 10105);
        config.addDefault("custom-model-data.gui.terminal.stats", 10106);
        config.addDefault("custom-model-data.gui.terminal.refresh", 10107);
        config.addDefault("custom-model-data.gui.stats.back", 10201);
        config.addDefault("custom-model-data.gui.network-select.item", 10301);
        config.addDefault("custom-model-data.gui.wireless-select.item", 10401);
        config.options().copyDefaults(true);
    }

    public NetworkMode getNetworkMode() {
        try {
            return NetworkMode.valueOf(config.getString("network-mode", "PLAYER").toUpperCase());
        } catch (IllegalArgumentException e) {
            return NetworkMode.PLAYER;
        }
    }

    public int getMaxChestsPerNetwork() {
        return config.getInt("max-chests-per-network");
    }

    public int getMaxTerminalsPerNetwork() {
        return config.getInt("max-terminals-per-network");
    }

    public int getMaxSenderChestsPerNetwork() {
        return config.getInt("max-sender-chests-per-network");
    }

    public int getSenderChestTransferInterval() {
        return config.getInt("sender-chest-transfer-interval-seconds");
    }

    public int getWirelessTerminalDurability() {
        return config.getInt("wireless-terminal-durability");
    }

    public Integer getOptionalCustomModelData(String path) {
        if (!config.isSet(path) || !config.isInt(path)) {
            return null;
        }
        return config.getInt(path);
    }

    public Integer getWirelessTerminalCustomModelData() {
        return getOptionalCustomModelData("custom-model-data.wireless-terminal");
    }

    public Integer getStorageWandCustomModelData() {
        return getOptionalCustomModelData("custom-model-data.wand");
    }

    public boolean isPermissionsEnabled() {
        return config.getBoolean("enable-permissions");
    }

    public boolean hasPermission(CommandSender sender, String permission) {
        return !isPermissionsEnabled() || sender.hasPermission(permission);
    }

    public boolean hasPrivilege(CommandSender sender, String permission) {
        return isPermissionsEnabled() && sender.hasPermission(permission);
    }

    public boolean isTrustSystemEnabled() {
        return config.getBoolean("enable-trust-system");
    }

    public int getAutoSaveInterval() {
        return config.getInt("auto-save-interval-minutes");
    }

    public String getLanguage() {
        return config.getString("language");
    }

    public boolean isMiningTaxEnabled() {
        return config.getBoolean("mining-tax.enabled");
    }

    public double getMiningTaxRate() {
        double rate = config.getDouble("mining-tax.rate", 0.0);
        return Math.max(0.0, Math.min(100.0, rate));
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        setDefaults();
        plugin.saveConfig();
    }
}
