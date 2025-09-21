package com.yourserver.networkstorage.listeners;

import com.yourserver.networkstorage.NetworkStoragePlugin;
import com.yourserver.networkstorage.gui.TerminalGUI;
import com.yourserver.networkstorage.listeners.WandListener;
import com.yourserver.networkstorage.storage.StorageNetwork;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestInteractListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, TerminalGUI> openTerminals;

    public ChestInteractListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.openTerminals = new HashMap<>();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null) {
            return;
        }

        // Don't interfere with wand usage
        ItemStack itemInHand = event.getItem();
        if (WandListener.isStorageWand(itemInHand)) {
            return;
        }

        // Check if the clicked block is a terminal
        if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
            StorageNetwork network = plugin.getNetworkManager().getNetworkByLocation(clickedBlock.getLocation());

            // Also check with normalized location for double chests
            if (network == null) {
                // Create a temporary network to use the normalize method
                StorageNetwork tempNetwork = new StorageNetwork("temp", "temp");
                Location normalizedLoc = tempNetwork.getNormalizedLocation(clickedBlock.getLocation());
                network = plugin.getNetworkManager().getNetworkByLocation(normalizedLoc);
            }

            if (network != null && (network.isTerminalInNetwork(clickedBlock.getLocation()) ||
                    network.isTerminalInNetwork(network.getNormalizedLocation(clickedBlock.getLocation())))) {

                // Open terminal GUI - everyone can access any network (as requested)
                event.setCancelled(true);
                TerminalGUI gui = new TerminalGUI(player, network, plugin);
                openTerminals.put(player.getUniqueId(), gui);
                gui.open();

                player.sendMessage(ChatColor.GREEN + "Accessing storage network...");
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        TerminalGUI terminal = openTerminals.get(player.getUniqueId());

        if (terminal == null || !event.getInventory().equals(terminal.getInventory())) {
            return;
        }

        event.setCancelled(true); // Prevent taking items directly

        int slot = event.getSlot();
        boolean isRightClick = event.isRightClick();
        boolean isLeftClick = event.isLeftClick();
        boolean isShiftClick = event.isShiftClick();

        terminal.handleClick(slot, isRightClick, isShiftClick, isLeftClick);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        TerminalGUI terminal = openTerminals.get(player.getUniqueId());

        if (terminal != null && event.getInventory().equals(terminal.getInventory())) {
            openTerminals.remove(player.getUniqueId());
        }
    }
}