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

                player.sendMessage(lang.get("network.access", player));
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

        int slot = event.getSlot();
        int rawSlot = event.getRawSlot();
        boolean isRightClick = event.isRightClick();
        boolean isLeftClick = event.isLeftClick();
        boolean isShiftClick = event.isShiftClick();

        // Check if player is clicking in their own inventory (bottom part)
        // Terminal GUI has 54 slots (0-53), player inventory starts at slot 54+
        if (rawSlot >= terminal.getInventory().getSize()) {
            // Player is clicking in their own inventory
            if (isShiftClick) {
                // Shift+Click in player inventory = deposit to network
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    event.setCancelled(true);

                    // Get the item directly from the player's inventory and slot
                    ItemStack itemToDeposit = null;
                    if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
                        itemToDeposit = player.getInventory().getItem(event.getSlot());
                    }
                    if (itemToDeposit == null || itemToDeposit.getType() == Material.AIR) {
                        // Fallback: Try to get the item from event.getCurrentItem
                        itemToDeposit = clickedItem;
                    }

                    // Calculate the slot in the player's inventory
                    int playerSlot = event.getSlot();
                    if (playerSlot < 0 || playerSlot >= player.getInventory().getSize()) {
                        plugin.getLogger().warning("Invalid player inventory slot: " + playerSlot);
                        return;
                    }

                    // Count the amount of the item in the network before depositing
                    int beforeCount = terminal.getNetwork().getItemCount(itemToDeposit);

                    // Remove the item from the inventory
                    player.getInventory().setItem(playerSlot, null);

                    // Try to deposit the item into the network
                    ItemStack remaining = terminal.getNetwork().addToNetwork(itemToDeposit.clone());

                    // Count the amount of the item in the network after depositing
                    int afterCount = terminal.getNetwork().getItemCount(itemToDeposit);
                    int actuallyStored = afterCount - beforeCount;

                    if (remaining == null || (remaining.getType() == Material.AIR) || remaining.getAmount() <= 0) {
                        if (actuallyStored == itemToDeposit.getAmount()) {
                            player.sendMessage(lang.get("network.deposit.success", player,
                                    String.valueOf(itemToDeposit.getAmount()), terminal.getItemDisplayName(itemToDeposit)));
                        } else {
                            player.sendMessage(lang.get("network.deposit.warning", player));
                            // Return item
                            player.getInventory().setItem(playerSlot, itemToDeposit);
                        }
                    } else {
                        int stored = itemToDeposit.getAmount() - remaining.getAmount();
                        if (stored > 0 && actuallyStored == stored) {
                            player.sendMessage(lang.get("network.deposit.partial", player,
                                    String.valueOf(stored), terminal.getItemDisplayName(itemToDeposit), String.valueOf(remaining.getAmount())));
                        } else {
                            player.sendMessage(lang.get("network.deposit.warning", player));
                            // Return item
                            player.getInventory().setItem(playerSlot, itemToDeposit);
                        }
                        // Return remaining items to player
                        player.getInventory().setItem(playerSlot, remaining);
                    }

                    // Query capacity via TerminalGUI
                    double capacity = terminal.getCurrentCapacityPercent();
                    if (capacity >= 80.0) {
                        player.sendMessage(lang.get("network.full.warning", player,
                                String.format("%.1f", capacity)));
                    }

                    // Refresh GUI
                    terminal.updateInventory();
                    return;
                }
            }
            // Allow normal inventory operations in player's inventory
            return;
        }

        // Player is clicking in the terminal GUI - prevent taking items directly
        event.setCancelled(true);

        // Handle terminal clicks
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

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            Location chestLoc = block.getLocation();
            StorageNetwork tempNetwork = new StorageNetwork("temp", "temp");
            Location normalizedLoc = tempNetwork.getNormalizedLocation(chestLoc);
            for (StorageNetwork network : plugin.getNetworkManager().getAllNetworks()) {
                if (network.isChestInNetwork(chestLoc)) {
                    network.removeChest(chestLoc);
                }
                if (network.isChestInNetwork(normalizedLoc)) {
                    network.removeChest(normalizedLoc);
                }
            }
        }
    }
}