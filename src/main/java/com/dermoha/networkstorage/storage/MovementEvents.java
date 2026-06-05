package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface MovementEvents {

    void sendDepositMessage(Player player, boolean full, int deposited, int returned, ItemStack template);

    void sendWithdrawMessage(Player player, NetworkMovement.WithdrawResult result, ItemStack template);

    void scheduleRefresh(TerminalGUI terminal, Runnable task);

    void dropAtFeet(Player player, ItemStack item);

    MovementEvents NOOP = new MovementEvents() {
        @Override public void sendDepositMessage(Player player, boolean full, int deposited, int returned, ItemStack template) {}
        @Override public void sendWithdrawMessage(Player player, NetworkMovement.WithdrawResult result, ItemStack template) {}
        @Override public void scheduleRefresh(TerminalGUI terminal, Runnable task) { task.run(); }
        @Override public void dropAtFeet(Player player, ItemStack item) {}
    };
}
