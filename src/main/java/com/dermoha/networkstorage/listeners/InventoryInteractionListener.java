package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.ConfigEditorGUI;
import com.dermoha.networkstorage.gui.NetworkSelectGUI;
import com.dermoha.networkstorage.gui.StatsGUI;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.gui.WirelessNetworkSelectGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.NetworkMovement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public class InventoryInteractionListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    public InventoryInteractionListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        if (holder instanceof NetworkSelectGUI selectGUI) {
            event.setCancelled(true);
            if (rawSlot >= 0 && rawSlot < topSize) {
                selectGUI.handleClick(event.getSlot());
            }
            return;
        }

        if (holder instanceof WirelessNetworkSelectGUI selectGUI) {
            event.setCancelled(true);
            if (rawSlot >= 0 && rawSlot < topSize) {
                selectGUI.handleClick(event.getSlot());
            }
            return;
        }

        if (holder instanceof StatsGUI statsGUI) {
            event.setCancelled(true);
            if (rawSlot >= 0 && rawSlot < topSize) {
                statsGUI.handleClick(event.getSlot());
            }
            return;
        }

        if (holder instanceof ConfigEditorGUI configEditor) {
            event.setCancelled(true);
            if (rawSlot >= 0 && rawSlot < topSize) {
                configEditor.handleClick(event);
            }
            return;
        }

        if (holder instanceof TerminalGUI terminal) {
            if (!plugin.getTerminalSessions().isCurrentTerminal(player, terminal)) {
                event.setCancelled(true);
                return;
            }

            event.setCancelled(true);

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

            if (rawSlot >= 0 && rawSlot < terminal.getInventory().getSize()) {
                terminal.handleClick(slot, isRightClick, isShiftClick, isLeftClick);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof NetworkSelectGUI
                || holder instanceof WirelessNetworkSelectGUI
                || holder instanceof StatsGUI
                || holder instanceof TerminalGUI)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleShiftClickDeposit(InventoryClickEvent event, TerminalGUI terminal, Player player) {
        if (!terminal.getNetwork().canAccess(player)) {
            player.closeInventory();
            player.sendMessage(lang.getMessage("trust.no_permission_access"));
            return;
        }

        if (event.getCurrentItem() == null) {
            return;
        }

        terminal.getNetwork().getMovement().depositFromPlayerToTerminal(
                player,
                new NetworkMovement.ItemSource.InvSlotSource(player, event.getSlot()),
                terminal);
    }
}
