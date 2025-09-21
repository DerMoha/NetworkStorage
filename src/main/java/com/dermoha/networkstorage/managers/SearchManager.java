package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages search functionality for terminal GUIs
 */
public class SearchManager implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, TerminalGUI> searchingPlayers;

    public SearchManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.searchingPlayers = new HashMap<>();

        // Register this as an event listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void startSearch(Player player, TerminalGUI gui) {
        searchingPlayers.put(player.getUniqueId(), gui);

        // Auto-cancel search after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (searchingPlayers.containsKey(player.getUniqueId())) {
                searchingPlayers.remove(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW + "Search timeout. Search cancelled.");
            }
        }, 600L); // 30 seconds
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (!searchingPlayers.containsKey(playerUUID)) {
            return; // Player is not searching
        }

        event.setCancelled(true); // Don't send the message to chat

        String message = event.getMessage().trim();
        TerminalGUI gui = searchingPlayers.remove(playerUUID);

        // Handle search on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.YELLOW + "Search cancelled.");
            } else {
                gui.setSearchFilter(message);
                player.sendMessage(ChatColor.GREEN + "Searching for: " + ChatColor.WHITE + message);
            }

            // Reopen the GUI
            gui.open();
        });
    }

    public void cancelSearch(Player player) {
        searchingPlayers.remove(player.getUniqueId());
    }

    public boolean isSearching(Player player) {
        return searchingPlayers.containsKey(player.getUniqueId());
    }

    public void cleanup() {
        searchingPlayers.clear();
    }
}