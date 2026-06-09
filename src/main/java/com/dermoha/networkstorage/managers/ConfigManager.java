package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.NetworkAccessRules;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager implements NetworkAccessRules {

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
        config.addDefault("protect-network-containers", true);
        config.addDefault("language", "en");
        config.addDefault("wireless-terminal-durability", 100);
        config.addDefault("wand-material", "BLAZE_ROD");
        config.addDefault("wireless-terminal-material", "RECOVERY_COMPASS");
        config.addDefault("terminal-block-type", "CHEST");
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
        config.addDefault("update-check.enabled", true);
        config.addDefault("update-check.notify-admins", true);
        config.options().copyDefaults(true);
    }

    public NetworkMode getNetworkMode() {
        try {
            return NetworkMode.valueOf(config.getString("network-mode", "PLAYER").toUpperCase());
        } catch (IllegalArgumentException e) {
            return NetworkMode.PLAYER;
        }
    }

    @Override
    public boolean isGlobalNetworkMode() {
        return getNetworkMode() == NetworkMode.GLOBAL;
    }

    public int getMaxChestsPerNetwork() {
        return getClampedInt("max-chests-per-network", 100, 1, 10_000);
    }

    public int getMaxTerminalsPerNetwork() {
        return getClampedInt("max-terminals-per-network", 100, 1, 10_000);
    }

    public int getMaxSenderChestsPerNetwork() {
        return getClampedInt("max-sender-chests-per-network", 100, 1, 10_000);
    }

    public int getSenderChestTransferInterval() {
        return getClampedInt("sender-chest-transfer-interval-seconds", 5, 1, 86_400);
    }

    public int getWirelessTerminalDurability() {
        return getClampedInt("wireless-terminal-durability", 100, 1, 1_000_000);
    }

    public Material getWandMaterial() {
        return parseMaterial("wand-material", Material.BLAZE_ROD);
    }

    public Material getWirelessTerminalMaterial() {
        return parseMaterial("wireless-terminal-material", Material.RECOVERY_COMPASS);
    }

    public Material getTerminalBlockType() {
        return parseMaterial("terminal-block-type", Material.CHEST);
    }

    public boolean isNetworkContainerBlock(Material material) {
        return material == Material.CHEST
                || material == Material.TRAPPED_CHEST
                || material == Material.COPPER_CHEST
                || material == Material.BARREL
                || material == Material.EXPOSED_COPPER_CHEST
                || material == Material.WEATHERED_COPPER_CHEST
                || material == Material.OXIDIZED_COPPER_CHEST
                || material == Material.WAXED_COPPER_CHEST
                || material == Material.WAXED_EXPOSED_COPPER_CHEST
                || material == Material.WAXED_WEATHERED_COPPER_CHEST
                || material == Material.WAXED_OXIDIZED_COPPER_CHEST
                || material == getTerminalBlockType();
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

    @Override
    public boolean hasPrivilege(CommandSender sender, String permission) {
        return isPermissionsEnabled() && sender.hasPermission(permission);
    }

    @Override
    public boolean isTrustSystemEnabled() {
        return config.getBoolean("enable-trust-system");
    }

    public boolean isNetworkContainerProtectionEnabled() {
        return config.getBoolean("protect-network-containers");
    }

    public int getAutoSaveInterval() {
        return getClampedInt("auto-save-interval-minutes", 5, 1, 10_080);
    }

    public String getLanguage() {
        return config.getString("language");
    }

    public boolean isUpdateCheckEnabled() {
        return config.getBoolean("update-check.enabled", true);
    }

    public boolean shouldNotifyAdminsOnUpdate() {
        return config.getBoolean("update-check.notify-admins", true);
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        setDefaults();
        plugin.saveConfig();
    }

    private int getClampedInt(String path, int defaultValue, int min, int max) {
        int value = config.getInt(path, defaultValue);
        int clamped = Math.max(min, Math.min(max, value));
        if (value != clamped) {
            plugin.getLogger().warning("Config value '" + path + "' must be between " + min + " and " + max + "; using " + clamped + ".");
        }
        return clamped;
    }

    private Material parseMaterial(String path, Material fallback) {
        String name = config.getString(path, fallback.name());
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material '" + name + "' for " + path + ", using " + fallback.name() + ".");
            return fallback;
        }
    }
}
