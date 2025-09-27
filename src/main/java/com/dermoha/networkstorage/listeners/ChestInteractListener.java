package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
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
import org.bukkit.inventory.InventoryHolder;
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

        ItemStack itemInHand = event.getItem();
        if (WandListener.isStorageWand(itemInHand, lang)) {
            return;
        }

        if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
            Network network = plugin.getNetworkManager().getNetworkByLocation(clickedBlock.getLocation());

            if (network != null && (network.isTerminalInNetwork(clickedBlock.getLocation()) || network.isTerminalInNetwork(network.getNormalizedLocation(clickedBlock.getLocation())))) {
                event.setCancelled(true);

                if (!network.canAccess(player)) {
                    player.sendMessage(lang.get("trust.no_permission_access"));
                    return;
                }

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
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof StatsGUI) {
            event.setCancelled(true);
            StatsGUI statsGUI = (StatsGUI) holder;
            statsGUI.handleClick(event.getSlot());
            return;
        }

        if (holder instanceof TerminalGUI) {
            TerminalGUI terminal = (TerminalGUI) holder;
            if (!terminal.equals(openTerminals.get(player.getUniqueId()))) {
                return;
            }

            event.setCancelled(true);

            int rawSlot = event.getRawSlot();
            int slot = event.getSlot();
            boolean isRightClick = event.isRightClick();
            boolean isLeftClick = event.isLeftClick();
            boolean isShiftClick = event.isShiftClick();

            if (rawSlot >= terminal.getInventory().getSize()) {
                if (isShiftClick) {
                    handleShiftClickDeposit(event, terminal, player);
                }
                return;
            }

            if (rawSlot < terminal.getInventory().getSize()) {
                terminal.handleClick(slot, isRightClick, isShiftClick, isLeftClick);
            }
        }
    }

    private void handleShiftClickDeposit(InventoryClickEvent event, TerminalGUI terminal, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        int playerSlot = event.getSlot();
        ItemStack itemToDeposit = player.getInventory().getItem(playerSlot);
        if (itemToDeposit == null || itemToDeposit.getType() == Material.AIR) {
            return;
        }

        int originalAmount = itemToDeposit.getAmount();
        ItemStack remaining = terminal.getNetwork().addToNetwork(itemToDeposit.clone());

        if (remaining == null || remaining.getAmount() == 0) {
            player.getInventory().setItem(playerSlot, null);
            player.sendMessage(lang.get("network.deposit.success", String.valueOf(originalAmount), terminal.getItemDisplayName(itemToDeposit)));
            terminal.getNetwork().recordItemsDeposited(player, originalAmount);
        } else {
            int depositedAmount = originalAmount - remaining.getAmount();
            if (depositedAmount > 0) {
                player.sendMessage(lang.get("network.deposit.partial", String.valueOf(depositedAmount), terminal.getItemDisplayName(itemToDeposit), String.valueOf(remaining.getAmount())));
                terminal.getNetwork().recordItemsDeposited(player, depositedAmount);
            }
            player.getInventory().setItem(playerSlot, remaining);
        }

        plugin.getServer().getScheduler().runTask(plugin, terminal::updateInventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();

        if (plugin.getSearchManager().isSearching(player) || holder instanceof StatsGUI) {
            return;
        }

        if (holder instanceof TerminalGUI) {
            openTerminals.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            Location chestLoc = block.getLocation();
            Network network = plugin.getNetworkManager().getNetworkByLocation(chestLoc);

            if (network != null) {
                Location normalizedLoc = network.getNormalizedLocation(chestLoc);
                boolean changed = false;
                if (network.isChestInNetwork(chestLoc)) {
                    network.removeChest(chestLoc);
                    changed = true;
                }
                if (network.isChestInNetwork(normalizedLoc)) {
                    network.removeChest(normalizedLoc);
                    changed = true;
                }
                if (network.isTerminalInNetwork(chestLoc)) {
                    network.removeTerminal(chestLoc);
                    changed = true;
                }
                if (network.isTerminalInNetwork(normalizedLoc)) {
                    network.removeTerminal(normalizedLoc);
                    changed = true;
                }
                if(changed) {
                    plugin.getNetworkManager().saveNetworks();
                }
            }
        }
    }
}
