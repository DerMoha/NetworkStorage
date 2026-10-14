package com.dermoha.networkstorage.integrations;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PlaceholderAPIHook {

    private final NetworkStoragePlugin plugin;
    private final boolean enabled;
    private UpdatePlaceholderExpansion expansion;

    public PlaceholderAPIHook(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.enabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (enabled) {
            plugin.getLogger().info("PlaceholderAPI detected; placeholders enabled.");
            expansion = new UpdatePlaceholderExpansion(plugin, this);
            expansion.register();
        }
    }

    public void close() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String onPlaceholderRequest(OfflinePlayer offlinePlayer, String identifier) {
        if (!enabled) {
            return null;
        }
        String lower = identifier.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("networkstorage_")) lower = lower.substring("networkstorage_".length());
        if (lower.startsWith("active_")) {
            return handleActivePlaceholder(offlinePlayer, lower.substring("active_".length()));
        }
        return null;
    }

    private String handleActivePlaceholder(OfflinePlayer offlinePlayer, String sub) {
        if (!java.util.Set.of("chests", "terminals", "sender_chests", "items", "capacity", "name", "description").contains(sub)) return null;
        if (offlinePlayer == null) return "";
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

}
