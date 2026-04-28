package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

        cancelSearchTask(playerId);

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
        cancelSearchTask(playerId);
    }

    private void cancelSearchTask(UUID playerId) {
        Integer taskId = searchTaskIds.remove(playerId);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
    }

    @EventHandler
    public void onPlayerChatLegacy(AsyncPlayerChatEvent event) {
        if (!isSearching(event.getPlayer())) {
            return;
        }

        event.setCancelled(true);
        handleSearchInput(event.getPlayer(), event.getMessage());
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!isSearching(event.getPlayer())) {
            return;
        }

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        handleSearchInput(event.getPlayer(), message);
    }

    private void handleSearchInput(Player player, String rawMessage) {
        UUID playerId = player.getUniqueId();
        TerminalGUI gui = searchingPlayers.remove(playerId);
        if (gui == null) {
            return;
        }

        cancelSearchTask(playerId);
        String message = rawMessage.trim();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(lang.getMessage("search.cancelled"));
            } else {
                gui.setSearchFilter(message);
                player.sendMessage(String.format(lang.getMessage("search.searching_for"), message));
            }

            if (gui.open()) {
                plugin.getChestInteractListener().addOpenTerminal(playerId, gui);
            }
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
