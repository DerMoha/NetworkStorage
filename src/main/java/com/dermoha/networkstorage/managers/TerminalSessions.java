package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.NetworkStorageConstants;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TerminalSessions implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Map<UUID, TerminalGUI> openTerminals = new HashMap<>();
    private final Set<UUID> transitioningToStats = new HashSet<>();
    private final Set<UUID> transitioningToSearch = new HashSet<>();
    private final Map<UUID, TerminalGUI> searchingPlayers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> searchTaskIds = new ConcurrentHashMap<>();

    public TerminalSessions(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    public boolean openTerminal(Player player, Network network) {
        return openTerminal(player, new TerminalGUI(player, network, plugin));
    }

    public boolean openTerminal(Player player, TerminalGUI gui) {
        UUID playerId = player.getUniqueId();
        if (!gui.open()) {
            openTerminals.remove(playerId);
            return false;
        }

        openTerminals.put(playerId, gui);
        return true;
    }

    public boolean isCurrentTerminal(Player player, TerminalGUI terminal) {
        return terminal.equals(openTerminals.get(player.getUniqueId()));
    }

    public void refreshNetwork(Network network) {
        for (TerminalGUI terminal : openTerminals.values()) {
            if (terminal.getNetwork() == network) {
                terminal.requestRefresh();
            }
        }
    }

    public void promptSearch(Player player, TerminalGUI gui) {
        UUID playerId = player.getUniqueId();
        startSearch(player, gui);
        transitioningToSearch.add(playerId);
        player.closeInventory();
        player.sendMessage(lang.getMessage("terminal.search.prompt"));
        player.sendMessage(lang.getMessage("terminal.search.cancel_hint"));
    }

    public void openStats(Player player, Network network, TerminalGUI previousGUI) {
        transitioningToStats.add(player.getUniqueId());
        new StatsGUI(player, network, plugin, previousGUI).open();
    }

    public void returnToTerminal(Player player, TerminalGUI previousGUI) {
        openTerminal(player, previousGUI);
    }

    public boolean isSearching(Player player) {
        return searchingPlayers.containsKey(player.getUniqueId());
    }

    public void cancelSearch(Player player) {
        UUID playerId = player.getUniqueId();
        searchingPlayers.remove(playerId);
        cancelSearchTask(playerId);
    }

    public void cleanup() {
        for (Integer taskId : searchTaskIds.values()) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        searchTaskIds.clear();
        searchingPlayers.clear();
        openTerminals.clear();
        transitioningToStats.clear();
        transitioningToSearch.clear();
    }

    private void startSearch(Player player, TerminalGUI gui) {
        UUID playerId = player.getUniqueId();
        cancelSearchTask(playerId);
        searchingPlayers.put(playerId, gui);

        int taskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (searchingPlayers.remove(playerId) != null) {
                player.sendMessage(lang.getMessage("search.timeout"));
            }
            searchTaskIds.remove(playerId);
        }, NetworkStorageConstants.SEARCH_TIMEOUT_TICKS).getTaskId();
        searchTaskIds.put(playerId, taskId);
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

            openTerminal(player, gui);
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof StatsGUI) {
            return;
        }

        if (!(holder instanceof TerminalGUI)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (transitioningToSearch.remove(playerId)) {
            openTerminals.remove(playerId);
            return;
        }
        if (transitioningToStats.remove(playerId)) {
            cancelSearch(player);
            openTerminals.remove(playerId);
            return;
        }
        cancelSearch(player);
        openTerminals.remove(playerId);
    }
}
