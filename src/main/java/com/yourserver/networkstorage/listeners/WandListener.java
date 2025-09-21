package com.yourserver.networkstorage.listeners;

import com.yourserver.networkstorage.NetworkStoragePlugin;
import com.yourserver.networkstorage.storage.StorageNetwork;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class WandListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public WandListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isStorageWand(item)) {
            return;
        }

        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        event.setCancelled(true);

        // Check if it's a chest
        if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
            handleChestClick(player, clickedBlock, event.getAction());
        } else {
            player.sendMessage(ChatColor.RED + "You can only use the storage wand on chests!");
        }
    }

    private void handleChestClick(Player player, Block chestBlock, Action action) {
        StorageNetwork network = plugin.getNetworkManager().getOrCreatePlayerNetwork(player);
        Location normalizedLoc = network.getNormalizedLocation(chestBlock.getLocation());

        if (action == Action.LEFT_CLICK_BLOCK) {
            // Add chest to network
            if (network.isChestInNetwork(normalizedLoc)) {
                player.sendMessage(ChatColor.YELLOW + "This chest is already in your storage network!");
                return;
            }

            // Check if the other half of a double chest is already in the network
            if (isOtherHalfInNetwork(chestBlock, network)) {
                player.sendMessage(ChatColor.YELLOW + "The other half of this double chest is already in your network!");
                player.sendMessage(ChatColor.GRAY + "Double chests are added as one unit.");
                return;
            }

            // Check if it would exceed the limit
            if (network.getChestLocations().size() >= plugin.getConfigManager().getMaxChestsPerNetwork()) {
                player.sendMessage(ChatColor.RED + "Your network has reached the maximum number of chests (" +
                        plugin.getConfigManager().getMaxChestsPerNetwork() + ")!");
                return;
            }

            network.addChest(normalizedLoc);
            plugin.getNetworkManager().saveNetworks();

            String chestType = getChestType(chestBlock);
            player.sendMessage(ChatColor.GREEN + "Added " + chestType + " to your storage network! (" +
                    network.getChestLocations().size() + " chests total)");

        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            // Remove chest from network or add as terminal
            if (player.isSneaking()) {
                // Sneaking + right click = add as terminal
                if (network.isTerminalInNetwork(normalizedLoc)) {
                    player.sendMessage(ChatColor.YELLOW + "This chest is already a terminal!");
                    return;
                }

                // Check if the other half of a double chest is already a terminal
                if (isOtherHalfInNetwork(chestBlock, network, true)) {
                    player.sendMessage(ChatColor.YELLOW + "The other half of this double chest is already a terminal!");
                    player.sendMessage(ChatColor.GRAY + "Double chests work as one unit.");
                    return;
                }

                // Check terminal limit
                if (network.getTerminalLocations().size() >= plugin.getConfigManager().getMaxTerminalsPerNetwork()) {
                    player.sendMessage(ChatColor.RED + "Your network has reached the maximum number of terminals (" +
                            plugin.getConfigManager().getMaxTerminalsPerNetwork() + ")!");
                    return;
                }

                network.addTerminal(normalizedLoc);
                plugin.getNetworkManager().saveNetworks();

                String chestType = getChestType(chestBlock);
                player.sendMessage(ChatColor.AQUA + "Added " + chestType + " terminal to your storage network! (" +
                        network.getTerminalLocations().size() + " terminals total)");

            } else {
                // Regular right click = remove from network
                boolean wasChest = network.isChestInNetwork(normalizedLoc);
                boolean wasTerminal = network.isTerminalInNetwork(normalizedLoc);

                if (!wasChest && !wasTerminal) {
                    player.sendMessage(ChatColor.RED + "This chest is not in your storage network!");
                    return;
                }

                network.removeChest(normalizedLoc);
                network.removeTerminal(normalizedLoc);
                plugin.getNetworkManager().saveNetworks();

                String chestType = getChestType(chestBlock);

                if (wasChest) {
                    player.sendMessage(ChatColor.RED + "Removed " + chestType + " from your storage network! (" +
                            network.getChestLocations().size() + " chests remaining)");
                } else {
                    player.sendMessage(ChatColor.RED + "Removed " + chestType + " terminal from your storage network! (" +
                            network.getTerminalLocations().size() + " terminals remaining)");
                }
            }
        }
    }

    /**
     * Checks if the other half of a double chest is already in the network
     */
    private boolean isOtherHalfInNetwork(Block chestBlock, StorageNetwork network) {
        return isOtherHalfInNetwork(chestBlock, network, false);
    }

    /**
     * Checks if the other half of a double chest is already in the network
     * @param chestBlock The chest block to check
     * @param network The network to check in
     * @param checkTerminals If true, check terminals; if false, check storage chests
     */
    private boolean isOtherHalfInNetwork(Block chestBlock, StorageNetwork network, boolean checkTerminals) {
        if (!(chestBlock.getState() instanceof Chest)) {
            return false;
        }

        // Check adjacent blocks for the other half of a double chest
        Location[] adjacentLocs = {
                chestBlock.getLocation().clone().add(1, 0, 0),  // East
                chestBlock.getLocation().clone().add(-1, 0, 0), // West
                chestBlock.getLocation().clone().add(0, 0, 1),  // South
                chestBlock.getLocation().clone().add(0, 0, -1)  // North
        };

        for (Location adjLoc : adjacentLocs) {
            if (adjLoc.getBlock().getState() instanceof Chest) {
                // Found the other half - check if it's in the network
                Location normalizedAdjLoc = network.getNormalizedLocation(adjLoc);
                if (checkTerminals) {
                    if (network.isTerminalInNetwork(normalizedAdjLoc)) {
                        return true;
                    }
                } else {
                    if (network.isChestInNetwork(normalizedAdjLoc)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private String getChestType(Block chestBlock) {
        if (chestBlock.getState() instanceof Chest) {
            Chest chest = (Chest) chestBlock.getState();
            // Check if it's a double chest by inventory size (54 slots = double chest)
            if (chest.getInventory().getSize() == 54) {
                return "double chest";
            }
        }
        return "chest";
    }

    public static boolean isStorageWand(ItemStack item) {
        if (item == null || item.getType() != Material.STICK) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }

        return meta.getDisplayName().equals(ChatColor.GOLD + "Storage Network Wand");
    }

    public static ItemStack createStorageWand() {
        ItemStack wand = new ItemStack(Material.STICK);
        ItemMeta meta = wand.getItemMeta();

        meta.setDisplayName(ChatColor.GOLD + "Storage Network Wand");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Left Click: Add chest to network",
                ChatColor.GRAY + "Right Click: Remove from network",
                ChatColor.GRAY + "Shift + Right Click: Add as terminal",
                ChatColor.DARK_GRAY + "Only works on chests!"
        ));

        wand.setItemMeta(meta);
        return wand;
    }
}