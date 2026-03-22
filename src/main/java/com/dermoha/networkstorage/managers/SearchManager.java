package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages search functionality for terminal GUIs
 */
public class SearchManager implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, TerminalGUI> searchingPlayers;
    private final ConcurrentMap<UUID, Integer> searchTaskIds;
    private final LanguageManager lang;

    public SearchManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.searchingPlayers = new ConcurrentHashMap<>();
        this.searchTaskIds = new ConcurrentHashMap<>();
        this.lang = plugin.getLanguageManager();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void startSearch(Player player, TerminalGUI gui) {
        UUID playerId = player.getUniqueId();

        Integer existingTaskId = searchTaskIds.get(playerId);
        if (existingTaskId != null) {
            plugin.getServer().getScheduler().cancelTask(existingTaskId);
            searchTaskIds.remove(playerId);
        }

        searchingPlayers.put(playerId, gui);

        int taskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (searchingPlayers.remove(playerId) != null) {
                player.sendMessage(lang.getMessage("search.timeout"));
            }
            searchTaskIds.remove(playerId);
        }, 600L).getTaskId();
        searchTaskIds.put(playerId, taskId);
    }

    public void cancelSearch(Player player) {
        UUID playerId = player.getUniqueId();
        searchingPlayers.remove(playerId);

        Integer taskId = searchTaskIds.remove(playerId);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
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
        searchTaskIds.remove(playerUUID);

        // Handle search on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(lang.getMessage("search.cancelled"));
            } else {
                gui.setSearchFilter(message);
                player.sendMessage(String.format(lang.getMessage("search.searching_for"), message));
            }

            // Reopen the GUI
            gui.open();
        });
    }

    public boolean isSearching(Player player) {
        return searchingPlayers.containsKey(player.getUniqueId());
    }

    public void cleanup() {
        for (Integer taskId : searchTaskIds.values()) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        searchTaskIds.clear();
        searchingPlayers.clear();
    }
}