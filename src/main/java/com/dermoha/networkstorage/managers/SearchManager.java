package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages search functionality for terminal GUIs and network creation/deletion
 */
public class SearchManager implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, TerminalGUI> searchingPlayers;
    private final Map<UUID, String> creatingNetwork;  // Players creating a network
    private final Map<UUID, String> deletingNetwork;  // Players confirming deletion
    private final Map<UUID, String> resettingNetwork; // Players confirming reset
    private final Map<UUID, String> renamingNetwork;  // Players renaming a network (stores old name)
    private final LanguageManager lang;

    public SearchManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.searchingPlayers = new HashMap<>();
        this.creatingNetwork = new HashMap<>();
        this.deletingNetwork = new HashMap<>();
        this.resettingNetwork = new HashMap<>();
        this.renamingNetwork = new HashMap<>();
        this.lang = plugin.getLanguageManager();

        // Register this as an event listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void startCreatingNetwork(Player player) {
        creatingNetwork.put(player.getUniqueId(), "");

        // Auto-cancel after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (creatingNetwork.containsKey(player.getUniqueId())) {
                creatingNetwork.remove(player.getUniqueId());
                player.sendMessage(lang.getMessage("network.create.timeout"));
            }
        }, 600L);
    }

    public void startDeletingNetwork(Player player, String networkName) {
        deletingNetwork.put(player.getUniqueId(), networkName);

        // Auto-cancel after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (deletingNetwork.containsKey(player.getUniqueId())) {
                deletingNetwork.remove(player.getUniqueId());
                player.sendMessage(lang.getMessage("network.delete.timeout"));
            }
        }, 600L);
    }

    public void startResettingNetwork(Player player, String networkName) {
        resettingNetwork.put(player.getUniqueId(), networkName);

        // Auto-cancel after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (resettingNetwork.containsKey(player.getUniqueId())) {
                resettingNetwork.remove(player.getUniqueId());
                player.sendMessage(lang.getMessage("reset_timeout"));
            }
        }, 600L);
    }

    public boolean isResettingNetwork(UUID playerUUID) {
        return resettingNetwork.containsKey(playerUUID);
    }

    public void cancelResettingNetwork(UUID playerUUID) {
        resettingNetwork.remove(playerUUID);
    }

    public void startRenamingNetwork(Player player, String oldNetworkName) {
        renamingNetwork.put(player.getUniqueId(), oldNetworkName);

        // Auto-cancel after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (renamingNetwork.containsKey(player.getUniqueId())) {
                renamingNetwork.remove(player.getUniqueId());
                player.sendMessage(lang.getMessage("network.rename.timeout"));
            }
        }, 600L);
    }

    public void startSearch(Player player, TerminalGUI gui) {
        searchingPlayers.put(player.getUniqueId(), gui);

        // Auto-cancel search after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (searchingPlayers.containsKey(player.getUniqueId())) {
                searchingPlayers.remove(player.getUniqueId());
                player.sendMessage(lang.getMessage("search.timeout"));
            }
        }, 600L); // 30 seconds
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Check if creating network
        if (creatingNetwork.containsKey(playerUUID)) {
            event.setCancelled(true);
            String networkName = event.getMessage().trim();
            creatingNetwork.remove(playerUUID);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (networkName.equalsIgnoreCase("cancel")) {
                    player.sendMessage(lang.getMessage("network.create.cancelled"));
                    return;
                }

                // Validate network name
                if (networkName.isEmpty() || networkName.length() > 32) {
                    player.sendMessage(lang.getMessage("network.create.invalid_name"));
                    return;
                }

                if (networkName.contains(" ")) {
                    player.sendMessage(lang.getMessage("network.create.no_spaces"));
                    return;
                }

                plugin.getNetworkManager().createNetwork(player, networkName);
            });
            return;
        }

        // Check if deleting network
        if (deletingNetwork.containsKey(playerUUID)) {
            event.setCancelled(true);
            String message = event.getMessage().trim();
            String networkToDelete = deletingNetwork.remove(playerUUID);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!message.equalsIgnoreCase(networkToDelete)) {
                    player.sendMessage(lang.getMessage("network.delete.cancelled"));
                    return;
                }

                if (plugin.getNetworkManager().deleteNetwork(player, networkToDelete)) {
                    player.sendMessage(lang.getMessage("network.delete.success").replace("%s", networkToDelete));
                } else {
                    player.sendMessage(lang.getMessage("network.delete.failed"));
                }
            });
            return;
        }

        // Check if renaming network
        if (renamingNetwork.containsKey(playerUUID)) {
            event.setCancelled(true);
            String newNetworkName = event.getMessage().trim();
            String oldNetworkName = renamingNetwork.remove(playerUUID);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (newNetworkName.equalsIgnoreCase("cancel")) {
                    player.sendMessage(lang.getMessage("network.rename.cancelled"));
                    return;
                }

                // Validate network name
                if (newNetworkName.isEmpty() || newNetworkName.length() > 32) {
                    player.sendMessage(lang.getMessage("network.create.invalid_name"));
                    return;
                }

                if (newNetworkName.contains(" ")) {
                    player.sendMessage(lang.getMessage("network.create.no_spaces"));
                    return;
                }

                if (plugin.getNetworkManager().renameNetwork(player, oldNetworkName, newNetworkName)) {
                    player.sendMessage(lang.getMessage("network.rename.success")
                        .replace("%old%", oldNetworkName)
                        .replace("%new%", newNetworkName));
                } else {
                    player.sendMessage(lang.getMessage("network.rename.failed"));
                }
            });
            return;
        }

        // Check if searching in terminal
        if (!searchingPlayers.containsKey(playerUUID)) {
            return; // Player is not searching
        }

        event.setCancelled(true); // Don't send the message to chat

        String message = event.getMessage().trim();
        TerminalGUI gui = searchingPlayers.remove(playerUUID);

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

    public void cancelSearch(Player player) {
        searchingPlayers.remove(player.getUniqueId());
    }

    public boolean isSearching(Player player) {
        return searchingPlayers.containsKey(player.getUniqueId());
    }

    public boolean isSearching(UUID playerUUID) {
        return searchingPlayers.containsKey(playerUUID);
    }

    public void cleanup() {
        searchingPlayers.clear();
        creatingNetwork.clear();
        deletingNetwork.clear();
        resettingNetwork.clear();
        renamingNetwork.clear();
    }
}