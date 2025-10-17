package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChestInteractListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, TerminalGUI> openTerminals;
    private final Set<UUID> transitioningToStats = new HashSet<>();
    private final LanguageManager lang;

    public ChestInteractListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.openTerminals = new HashMap<>();
        this.lang = plugin.getLanguageManager();
    }

    public void addOpenTerminal(UUID playerId, TerminalGUI gui) { openTerminals.put(playerId, gui); }
    public void setTransitioningToStats(UUID playerId) { transitioningToStats.add(playerId); }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null) return;

        ItemStack itemInHand = event.getItem();
        if (WandListener.isStorageWand(itemInHand, lang)) return;

        if (clickedBlock.getType() == Material.CHEST || clickedBlock.getType() == Material.TRAPPED_CHEST) {
            Network network = plugin.getNetworkManager().getNetworkByLocation(clickedBlock.getLocation());

            if (network != null && (network.isTerminalInNetwork(clickedBlock.getLocation()) || network.isTerminalInNetwork(network.getNormalizedLocation(clickedBlock.getLocation())))) {
                event.setCancelled(true);

                if (!network.canAccess(player)) {
                    player.sendMessage(lang.getMessage("trust.no_permission_access"));
                    return;
                }

                if (player.isSneaking() && itemInHand != null && itemInHand.getType() != Material.AIR) {
                    handleQuickDeposit(player, network, itemInHand);
                    return;
                }

                TerminalGUI gui = new TerminalGUI(player, network, plugin);
                addOpenTerminal(player.getUniqueId(), gui);
                player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
                gui.open();

                player.sendMessage(lang.getMessage("network.access"));
            }
        }
    }

    private void handleQuickDeposit(Player player, Network network, ItemStack itemInHand) {
        int originalAmount = itemInHand.getAmount();
        ItemStack remaining = network.addToNetwork(itemInHand.clone());

        if (remaining == null || remaining.getAmount() == 0) {
            player.getInventory().setItemInMainHand(null);
            player.sendMessage(String.format(lang.getMessage("network.deposit.success"), originalAmount, getItemDisplayName(itemInHand)));
            network.recordItemsDeposited(player, originalAmount);
        } else {
            int depositedAmount = originalAmount - remaining.getAmount();
            if (depositedAmount > 0) {
                player.sendMessage(String.format(lang.getMessage("network.deposit.partial"), depositedAmount, getItemDisplayName(itemInHand), remaining.getAmount()));
                network.recordItemsDeposited(player, depositedAmount);
            }
            itemInHand.setAmount(remaining.getAmount());
        }
    }

    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().toString().replace('_', ' ').toLowerCase();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof StatsGUI statsGUI) {
            event.setCancelled(true);
            statsGUI.handleClick(event.getSlot());
            return;
        }

        if (holder instanceof TerminalGUI terminal) {
            if (!terminal.equals(openTerminals.get(player.getUniqueId()))) return;

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
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        int playerSlot = event.getSlot();
        ItemStack itemToDeposit = player.getInventory().getItem(playerSlot);
        if (itemToDeposit == null || itemToDeposit.getType() == Material.AIR) return;

        int originalAmount = itemToDeposit.getAmount();
        ItemStack remaining = terminal.getNetwork().addToNetwork(itemToDeposit.clone());

        if (remaining == null || remaining.getAmount() == 0) {
            player.getInventory().setItem(playerSlot, null);
            player.sendMessage(String.format(lang.getMessage("network.deposit.success"), originalAmount, terminal.getItemDisplayName(itemToDeposit)));
            terminal.getNetwork().recordItemsDeposited(player, originalAmount);
        } else {
            int depositedAmount = originalAmount - remaining.getAmount();
            if (depositedAmount > 0) {
                player.sendMessage(String.format(lang.getMessage("network.deposit.partial"), depositedAmount, terminal.getItemDisplayName(itemToDeposit), remaining.getAmount()));
                terminal.getNetwork().recordItemsDeposited(player, depositedAmount);
            }
            player.getInventory().setItem(playerSlot, remaining);
        }
        plugin.getServer().getScheduler().runTask(plugin, terminal::updateInventory);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof TerminalGUI) {
            if (plugin.getSearchManager().isSearching(player) || transitioningToStats.remove(player.getUniqueId())) {
                return;
            }
            openTerminals.remove(player.getUniqueId());
        } else if (holder instanceof Chest) {
            Location chestLoc = ((Chest) holder).getLocation();
            Network network = plugin.getNetworkManager().getNetworkByLocation(chestLoc);
            if (network != null && network.isChestInNetwork(chestLoc)) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, network::rebuildCache);
            }
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
