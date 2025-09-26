package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
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
    private final LanguageManager lang;

    public ChestInteractListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.openTerminals = new HashMap<>();
        this.lang = plugin.getLanguageManager();
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
        if (WandListener.isStorageWand(itemInHand, lang)) {
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

                player.sendMessage(lang.get("network.access"));
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

        event.setCancelled(true); // Cancel the event immediately to prevent any unwanted item movement

        int rawSlot = event.getRawSlot();
        int slot = event.getSlot();
        boolean isRightClick = event.isRightClick();
        boolean isLeftClick = event.isLeftClick();
        boolean isShiftClick = event.isShiftClick();

        // Check if player is clicking in their own inventory (bottom part)
        if (rawSlot >= terminal.getInventory().getSize()) {
            // Player is clicking in their own inventory
            if (isShiftClick) {
                handleShiftClickDeposit(event, terminal, player);
            }
            return;
        }

        // Handle terminal GUI clicks (only if clicking in the top inventory)
        if (rawSlot < terminal.getInventory().getSize()) {
            // Ensure we're working with the correct slot number for the terminal GUI
            terminal.handleClick(slot, isRightClick, isShiftClick, isLeftClick);
        }
    }

    private void handleShiftClickDeposit(InventoryClickEvent event, TerminalGUI terminal, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        // Get the item directly from the player's inventory
        int playerSlot = event.getSlot();
        ItemStack itemToDeposit = player.getInventory().getItem(playerSlot);
        if (itemToDeposit == null || itemToDeposit.getType() == Material.AIR) {
            return;
        }

        int originalAmount = itemToDeposit.getAmount();

        // Try to deposit the item into the network
        ItemStack remaining = terminal.getNetwork().addToNetwork(itemToDeposit.clone());

        if (remaining == null || remaining.getAmount() == 0) {
            // Successfully deposited the full stack
            player.getInventory().setItem(playerSlot, null);
            player.sendMessage(lang.get("network.deposit.success", String.valueOf(originalAmount), terminal.getItemDisplayName(itemToDeposit)));
        } else {
            // Partially deposited or not deposited at all
            int storedAmount = originalAmount - remaining.getAmount();
            if (storedAmount > 0) {
                player.sendMessage(lang.get("network.deposit.partial", String.valueOf(storedAmount), terminal.getItemDisplayName(itemToDeposit), String.valueOf(remaining.getAmount())));
            }
            // Give back the remainder
            player.getInventory().setItem(playerSlot, remaining);
        }


        // Check network capacity
        double capacity = terminal.getCurrentCapacityPercent();
        if (capacity >= 80.0) {
            player.sendMessage(lang.get("network.full.warning", String.format("%.1f", capacity)));
        }

        // Update the GUI
        plugin.getServer().getScheduler().runTask(plugin, terminal::updateInventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();

        // If the player is closing the inventory to start a search, don't remove the terminal instance.
        if (plugin.getSearchManager().isSearching(player)) {
            return;
        }

        TerminalGUI terminal = openTerminals.get(player.getUniqueId());

        if (terminal != null && event.getInventory().equals(terminal.getInventory())) {
            openTerminals.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            Location chestLoc = block.getLocation();
            StorageNetwork tempNetwork = new StorageNetwork("temp", "temp"); // Helper to get normalized location
            Location normalizedLoc = tempNetwork.getNormalizedLocation(chestLoc);

            // It's possible the chest is part of any network, so we have to check all of them.
            for (StorageNetwork network : plugin.getNetworkManager().getAllNetworks()) {
                // Check both original and normalized locations, just in case.
                if (network.isChestInNetwork(chestLoc)) {
                    network.removeChest(chestLoc);
                }
                if (network.isChestInNetwork(normalizedLoc)) {
                    network.removeChest(normalizedLoc);
                }
                if (network.isTerminalInNetwork(chestLoc)) {
                    network.removeTerminal(chestLoc);
                }
                if (network.isTerminalInNetwork(normalizedLoc)) {
                    network.removeTerminal(normalizedLoc);
                }
            }
        }
    }
}
