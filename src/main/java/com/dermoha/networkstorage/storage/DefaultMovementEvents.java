package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultMovementEvents implements MovementEvents {

    private final JavaPlugin plugin;
    private final LanguageManager lang;

    public DefaultMovementEvents(JavaPlugin plugin, LanguageManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void sendDepositMessage(Player player, boolean full, int deposited, int returned, ItemStack template) {
        if (deposited <= 0) {
            return;
        }
        String displayName = ItemUtils.getItemDisplayName(template);
        if (full) {
            player.sendMessage(String.format(lang.getMessage("network.deposit.success"), deposited, displayName));
        } else {
            player.sendMessage(String.format(lang.getMessage("network.deposit.partial"), deposited, displayName, returned));
        }
    }

    @Override
    public void sendWithdrawMessage(Player player, NetworkMovement.WithdrawResult result, ItemStack template) {
        String displayName = ItemUtils.getItemDisplayName(template);
        if (result.noItems()) {
            player.sendMessage(lang.getMessage("terminal.no_items"));
            return;
        }
        if (result.withdrawn() <= 0) {
            player.sendMessage(lang.getMessage("terminal.inventory_full_returned"));
            return;
        }
        player.sendMessage(String.format(lang.getMessage("terminal.took_items"), result.withdrawn(), displayName));
        if (result.dropped() > 0) {
            player.sendMessage(lang.getMessage("terminal.items_dropped"));
        } else if (result.returned() > 0) {
            player.sendMessage(lang.getMessage("terminal.inventory_full_returned"));
        }
    }

    @Override
    public void scheduleRefresh(TerminalGUI terminal, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public void dropAtFeet(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        player.getWorld().dropItemNaturally(player.getLocation(), item);
    }
}
