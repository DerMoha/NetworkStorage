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
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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

    /**
     * Inventory events are observed at MONITOR and invalidated on the next
     * tick, after Bukkit has applied the click/drag/hopper/close mutation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        invalidateNextTick(event.getClickedInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        invalidateNextTick(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        invalidateNextTick(event.getSource());
        invalidateNextTick(event.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        invalidateNextTick(event.getInventory());
    }

    private void invalidateNextTick(Inventory inventory) {
        Location location = getContainerLocation(inventory);
        if (location == null) {
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            Network network = plugin.getNetworkManager().getNetworkByLocation(location);
            if (network != null && (network.isChestInNetwork(location)
                    || network.isChestInNetwork(plugin.getNetworkManager().getNormalizedLocation(location))
                    || network.isSenderChestInNetwork(location))) {
                network.invalidateItemCache();
                if (plugin.getTerminalSessions() != null) {
                    plugin.getTerminalSessions().refreshNetwork(network);
                }
            }
        });
    }

    private Location getContainerLocation(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) {
            return container.getBlock().getLocation();
        }
        if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof Container left) {
                return left.getBlock().getLocation();
            }
            if (doubleChest.getRightSide() instanceof Container right) {
                return right.getBlock().getLocation();
            }
        }
        return inventory.getLocation();
    }
}
