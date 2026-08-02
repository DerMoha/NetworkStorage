package com.dermoha.networkstorage.integrations;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PlaceholderAPIHook {

    private final NetworkStoragePlugin plugin;
    private final boolean enabled;

    public PlaceholderAPIHook(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.enabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (enabled) {
            plugin.getLogger().info("PlaceholderAPI detected; placeholders enabled.");
            new UpdatePlaceholderExpansion(plugin).register();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String onPlaceholderRequest(OfflinePlayer offlinePlayer, String identifier) {
        if (!enabled) {
            return null;
        }
        if (offlinePlayer == null) {
            return "";
        }
        String lower = identifier.toLowerCase();
        if (lower.startsWith("networkstorage_active_")) {
            return handleActivePlaceholder(offlinePlayer, lower.substring("networkstorage_active_".length()));
        }
        if (lower.startsWith("networkstorage_top_")) {
            return handleTopPlaceholder(lower.substring("networkstorage_top_".length()));
        }
        return null;
    }

    private String handleActivePlaceholder(OfflinePlayer offlinePlayer, String sub) {
        Player online = offlinePlayer.getPlayer();
        if (online == null) {
            return "0";
        }
        Network network = plugin.getNetworkManager().getPlayerNetwork(online);
        if (network == null) {
            return "0";
        }
        return switch (sub) {
            case "chests" -> String.valueOf(network.getChestLocations().size());
            case "terminals" -> String.valueOf(network.getTerminalLocations().size());
            case "sender_chests" -> String.valueOf(network.getSenderChestLocations().size());
            case "items" -> String.valueOf(network.getLastCompleteStoredAmount());
            case "capacity" -> String.format("%.1f", network.getCapacityPercent());
            case "name" -> network.getName();
            case "description" -> network.getDescription();
            default -> null;
        };
    }

    private String handleTopPlaceholder(String sub) {
        return "0";
    }
}
