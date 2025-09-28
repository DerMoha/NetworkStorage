package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
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
    private final LanguageManager lang;

    public WandListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isStorageWand(item, lang)) {
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

        if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
            handleChestClick(player, clickedBlock, event.getAction());
        } else {
            player.sendMessage(lang.getMessage("wand.only_chest"));
        }
    }

    private void handleChestClick(Player player, Block chestBlock, Action action) {
        Network network = plugin.getNetworkManager().getOrCreatePlayerNetwork(player);
        if (network == null) {
            player.sendMessage(lang.getMessage("network.error.create"));
            return;
        }
        Location normalizedLoc = network.getNormalizedLocation(chestBlock.getLocation());

        if (action == Action.LEFT_CLICK_BLOCK) {
            if (network.isChestInNetwork(normalizedLoc)) {
                player.sendMessage(lang.getMessage("wand.chest.already_in_network"));
                return;
            }

            if (isOtherHalfInNetwork(chestBlock, network)) {
                player.sendMessage(lang.getMessage("wand.double_chest.other_half_in_network"));
                player.sendMessage(lang.getMessage("wand.double_chest.unit_hint"));
                return;
            }

            if (network.getChestLocations().size() >= plugin.getConfigManager().getMaxChestsPerNetwork()) {
                player.sendMessage(String.format(lang.getMessage("wand.chest.limit_reached"), plugin.getConfigManager().getMaxChestsPerNetwork()));
                return;
            }

            network.addChest(normalizedLoc);
            plugin.getNetworkManager().saveNetworks();

            String chestType = getChestType(chestBlock);
            player.sendMessage(String.format(lang.getMessage("wand.chest.added"), chestType, network.getChestLocations().size()));

        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                if (network.isTerminalInNetwork(normalizedLoc)) {
                    player.sendMessage(lang.getMessage("wand.terminal.already"));
                    return;
                }

                if (isOtherHalfInNetwork(chestBlock, network, true)) {
                    player.sendMessage(lang.getMessage("wand.terminal.other_half"));
                    player.sendMessage(lang.getMessage("wand.terminal.unit_hint"));
                    return;
                }

                if (network.getTerminalLocations().size() >= plugin.getConfigManager().getMaxTerminalsPerNetwork()) {
                    player.sendMessage(String.format(lang.getMessage("wand.terminal.limit_reached"), plugin.getConfigManager().getMaxTerminalsPerNetwork()));
                    return;
                }

                network.addTerminal(normalizedLoc);
                plugin.getNetworkManager().saveNetworks();

                String chestType = getChestType(chestBlock);
                player.sendMessage(String.format(lang.getMessage("wand.terminal.added"), chestType, network.getTerminalLocations().size()));

            } else {
                boolean wasChest = network.isChestInNetwork(normalizedLoc);
                boolean wasTerminal = network.isTerminalInNetwork(normalizedLoc);

                if (!wasChest && !wasTerminal) {
                    player.sendMessage(lang.getMessage("wand.chest.not_in_network"));
                    return;
                }

                network.removeChest(normalizedLoc);
                network.removeTerminal(normalizedLoc);
                plugin.getNetworkManager().saveNetworks();

                String chestType = getChestType(chestBlock);

                if (wasChest) {
                    player.sendMessage(String.format(lang.getMessage("wand.chest.removed"), chestType, network.getChestLocations().size()));
                } else {
                    player.sendMessage(String.format(lang.getMessage("wand.terminal.removed"), chestType, network.getTerminalLocations().size()));
                }
            }
        }
    }

    private boolean isOtherHalfInNetwork(Block chestBlock, Network network) {
        return isOtherHalfInNetwork(chestBlock, network, false);
    }

    private boolean isOtherHalfInNetwork(Block chestBlock, Network network, boolean checkTerminals) {
        if (!(chestBlock.getState() instanceof Chest)) {
            return false;
        }

        Location[] adjacentLocs = {
                chestBlock.getLocation().clone().add(1, 0, 0),
                chestBlock.getLocation().clone().add(-1, 0, 0),
                chestBlock.getLocation().clone().add(0, 0, 1),
                chestBlock.getLocation().clone().add(0, 0, -1)
        };

        for (Location adjLoc : adjacentLocs) {
            if (adjLoc.getBlock().getState() instanceof Chest) {
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
            if (chest.getInventory().getSize() == 54) {
                return "double chest";
            }
        }
        return "chest";
    }

    public static ItemStack createStorageWand(LanguageManager lang) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(lang.getMessage("wand.name"));
            meta.setLore(Arrays.asList(
                    lang.getMessage("wand.lore1"),
                    lang.getMessage("wand.lore2"),
                    lang.getMessage("wand.lore3")
            ));
            wand.setItemMeta(meta);
        }

        return wand;
    }

    public static boolean isStorageWand(ItemStack item, LanguageManager lang) {
        if (item == null || item.getType() != Material.BLAZE_ROD) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() &&
                meta.getDisplayName().equals(lang.getMessage("wand.name"));
    }
}
