package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkMovement;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class NetworkContainerListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    public NetworkContainerListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        ItemStack itemInHand = event.getItem();
        if (WandListener.isStorageWand(itemInHand, plugin)) {
            return;
        }

        if (!plugin.getConfigManager().isNetworkContainerBlock(clickedBlock.getType())) {
            return;
        }

        Network network = plugin.getNetworkManager().getNetworkByLocation(clickedBlock.getLocation());
        Location normalizedLoc = network != null ? plugin.getNetworkManager().getNormalizedLocation(clickedBlock.getLocation()) : null;

        if (network != null && (network.isTerminalInNetwork(clickedBlock.getLocation()) || network.isTerminalInNetwork(normalizedLoc))) {
            event.setCancelled(true);

            if (!network.canAccess(player)) {
                player.sendMessage(lang.getMessage("trust.no_permission_access"));
                return;
            }

            if (player.isSneaking() && itemInHand != null && itemInHand.getType() != Material.AIR) {
                handleQuickDeposit(player, network, itemInHand, event.getHand());
                return;
            }

            TerminalGUI gui = new TerminalGUI(player, network, plugin);
            if (plugin.getTerminalSessions().openTerminal(player, gui)) {
                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
                player.sendMessage(lang.getMessage("network.access"));
            }
            return;
        }

        if (network != null && plugin.getConfigManager().isNetworkContainerProtectionEnabled() && !network.canAccess(player)) {
            event.setCancelled(true);
            player.sendMessage(lang.getMessage("trust.no_permission_access"));
        }
    }

    private void handleQuickDeposit(Player player, Network network, ItemStack itemInHand, EquipmentSlot hand) {
        network.getMovement().depositFromPlayer(player, new NetworkMovement.ItemSource.HandSource(player, hand));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.getConfigManager().isNetworkContainerBlock(block.getType())) {
            return;
        }

        Network network = plugin.getNetworkManager().getNetworkByLocation(block.getLocation());
        if (network == null) {
            return;
        }

        Player breaker = event.getPlayer();
        boolean isOwner = network.getOwner().equals(breaker.getUniqueId());
        boolean isAdmin = plugin.getConfigManager().hasPrivilege(breaker, "networkstorage.admin");
        if (!isOwner && !isAdmin) {
            event.setCancelled(true);
            return;
        }

        if (plugin.getNetworkManager().removeTrackedLocation(network, block.getLocation())) {
            plugin.getNetworkManager().saveNetworks();
        }
    }
}
