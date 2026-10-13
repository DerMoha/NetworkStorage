package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.NetworkStorageConstants;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalSessions implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Map<UUID, TerminalSession> sessions = new ConcurrentHashMap<>();

    private TerminalSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), ignored -> new TerminalSession());
    }

    public TerminalSessions(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    public boolean openTerminal(Player player, Network network) {
        return openTerminal(player, new TerminalGUI(player, network, plugin,
                plugin.getNetworkManager().getPlayerSortType(player, plugin.getConfigManager().getDefaultSort())));
    }

    public boolean openTerminal(Player player, TerminalGUI gui) {
        TerminalSession session = session(player);
        if (!gui.open()) {
            session.close();
            return false;
        }

        session.opened(gui);
        return true;
    }

    public boolean isCurrentTerminal(Player player, TerminalGUI terminal) {
        TerminalSession session = sessions.get(player.getUniqueId());
        return session != null && terminal.equals(session.visibleTerminal());
    }

    public void refreshNetwork(Network network) {
        for (TerminalSession session : sessions.values()) {
            TerminalGUI terminal = session.visibleTerminal();
            if (terminal != null && terminal.getNetwork() == network) {
                terminal.requestRefresh();
            }
        }
    }

    public void promptSearch(Player player, TerminalGUI gui) {
        startSearch(player, gui);
        player.closeInventory();
        player.sendMessage(lang.getMessage("terminal.search.prompt"));
        player.sendMessage(lang.getMessage("terminal.search.cancel_hint"));
    }

    public void openStats(Player player, Network network, TerminalGUI previousGUI) {
        session(player).beginStats();
        new StatsGUI(player, network, plugin, previousGUI).open();
    }

    public void returnToTerminal(Player player, TerminalGUI previousGUI) {
        session(player).returnFromStats();
        if (!openTerminal(player, previousGUI)) {
            finishPendingWirelessBreak(player);
        }
    }

    public void scheduleWirelessTerminalBreak(Player player, EquipmentSlot hand, ItemStack item) {
        session(player).pendingBreak(hand, item);
    }

    public boolean isSearching(Player player) {
        TerminalSession session = sessions.get(player.getUniqueId());
        return session != null && session.isSearching();
    }

    public void cancelSearch(Player player) {
        TerminalSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.cancelSearch();
            cancelSearchTask(session);
        }
    }

    public void cleanup() {
        for (UUID playerId : Set.copyOf(sessions.keySet())) {
            TerminalSession session = sessions.get(playerId);
            session.close();
            cancelSearchTask(session);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) finishPendingWirelessBreak(player);
        }
        sessions.clear();
    }

    private void startSearch(Player player, TerminalGUI gui) {
        TerminalSession session = session(player);
        cancelSearchTask(session);
        session.beginSearch(gui);
        session.searchTask(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (session.expireSearch()) {
                player.sendMessage(lang.getMessage("search.timeout"));
                finishPendingWirelessBreak(player);
            }
            session.takeSearchTask();
        }, NetworkStorageConstants.SEARCH_TIMEOUT_TICKS).getTaskId());
    }

    private void cancelSearchTask(TerminalSession session) {
        int taskId = session.takeSearchTask();
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
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
        TerminalSession session = sessions.get(playerId);
        TerminalSession.SearchInput input = session == null ? null : session.claimSearch();
        if (input == null) {
            return;
        }

        String message = rawMessage.trim();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (sessions.get(playerId) != session || !session.acceptsSearchInput(input)) return;
            cancelSearchTask(session);
            TerminalGUI gui = input.terminal();
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(lang.getMessage("search.cancelled"));
            } else {
                gui.setSearchFilter(message);
                player.sendMessage(String.format(lang.getMessage("search.searching_for"), message));
            }

            if (!openTerminal(player, gui)) {
                finishPendingWirelessBreak(player);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelSearch(player);
        TerminalSession session = sessions.get(player.getUniqueId());
        if (session != null) session.close();
        finishPendingWirelessBreak(player);
        sessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        TerminalSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        InventoryHolder holder = event.getInventory().getHolder();
        boolean finished;
        if (holder instanceof StatsGUI) {
            finished = session.closeStats();
        } else if (holder instanceof TerminalGUI) {
            finished = session.closeTerminal();
        } else {
            return;
        }
        if (finished) {
            cancelSearchTask(session);
            finishPendingWirelessBreak(player);
        }
    }

    private void finishPendingWirelessBreak(Player player) {
        TerminalSession session = sessions.get(player.getUniqueId());
        TerminalSession.PendingWirelessBreak pending = session == null ? null : session.takePendingBreak();
        if (pending == null) {
            return;
        }

        WirelessTerminalListener listener = plugin.getWirelessTerminalListener();
        if (listener != null && listener.breakWirelessTerminal(player, pending.hand(), pending.item())) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
    }

}
