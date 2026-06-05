package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public final class NetworkMovement {

    public interface ItemSource {
        ItemStack read();
        void write(ItemStack remaining);
        record HandSource(Player player, EquipmentSlot hand) implements ItemSource {
            @Override public ItemStack read() {
                return hand == EquipmentSlot.OFF_HAND
                        ? player.getInventory().getItemInOffHand()
                        : player.getInventory().getItemInMainHand();
            }
            @Override public void write(ItemStack remaining) {
                if (hand == EquipmentSlot.OFF_HAND) {
                    player.getInventory().setItemInOffHand(remaining);
                } else {
                    player.getInventory().setItemInMainHand(remaining);
                }
            }
        }
        record InvSlotSource(Player player, int slot) implements ItemSource {
            @Override public ItemStack read() { return player.getInventory().getItem(slot); }
            @Override public void write(ItemStack remaining) { player.getInventory().setItem(slot, remaining); }
        }
        record InventorySlotSource(Inventory inventory, int slot) implements ItemSource {
            @Override public ItemStack read() { return inventory.getItem(slot); }
            @Override public void write(ItemStack remaining) { inventory.setItem(slot, remaining); }
        }
    }

    public record DepositResult(int deposited, int returned) {
        public boolean full() { return returned == 0; }
    }

    public record WithdrawResult(int removedAmount, int withdrawn, int returned, int dropped) {
        public boolean noItems() { return removedAmount == 0; }
        public boolean full() { return withdrawn > 0 && returned == 0 && dropped == 0; }
        public boolean allReturned() { return removedAmount > 0 && withdrawn == 0; }
    }

    public record AbsorbResult(int absorbed, int leftover) {}

    private final Network network;
    private final MovementEvents events;

    public NetworkMovement(Network network, MovementEvents events) {
        this.network = network;
        this.events = events;
    }

    public DepositResult depositFromPlayer(Player player, ItemSource source) {
        ItemStack original = source.read();
        if (original == null || original.getType().isAir()) {
            return new DepositResult(0, 0);
        }

        int originalAmount = original.getAmount();
        ItemStack remaining = network.addToNetwork(original.clone());
        int returned = remaining == null ? 0 : remaining.getAmount();
        int deposited = originalAmount - returned;

        source.write(remaining);

        if (deposited > 0) {
            network.recordItemsDeposited(player, deposited);
        }
        events.sendDepositMessage(player, returned == 0, deposited, returned, original);

        return new DepositResult(deposited, returned);
    }

    public DepositResult depositFromPlayerToTerminal(Player player, ItemSource source, TerminalGUI terminal) {
        DepositResult result = depositFromPlayer(player, source);
        if (result.deposited() > 0 || result.returned() > 0) {
            events.scheduleRefresh(terminal, terminal::updateInventory);
        }
        return result;
    }

    public WithdrawResult withdrawToPlayer(Player player, ItemStack template, int requestedAmount, TerminalGUI terminal) {
        if (requestedAmount <= 0 || template == null || template.getType().isAir()) {
            events.sendWithdrawMessage(player, new WithdrawResult(0, 0, 0, 0), template);
            return new WithdrawResult(0, 0, 0, 0);
        }

        ItemStack requested = template.clone();
        requested.setAmount(requestedAmount);
        ItemStack removed = network.removeFromNetwork(requested, requestedAmount);
        if (removed == null || removed.getAmount() <= 0) {
            events.sendWithdrawMessage(player, new WithdrawResult(0, 0, 0, 0), template);
            return new WithdrawResult(0, 0, 0, 0);
        }

        int removedAmount = removed.getAmount();
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(removed.clone());
        int returnedToNetwork = 0;
        int dropped = 0;

        for (ItemStack overflowItem : overflow.values()) {
            int overflowAmount = overflowItem.getAmount();
            ItemStack remaining = network.addToNetwork(overflowItem.clone());
            if (remaining == null || remaining.getAmount() == 0) {
                returnedToNetwork += overflowAmount;
            } else {
                returnedToNetwork += overflowAmount - remaining.getAmount();
                int leftover = remaining.getAmount();
                if (leftover > 0) {
                    events.dropAtFeet(player, remaining);
                    dropped += leftover;
                }
            }
        }

        int withdrawn = removedAmount - returnedToNetwork;
        WithdrawResult result = new WithdrawResult(removedAmount, withdrawn, returnedToNetwork, dropped);
        if (withdrawn > 0) {
            network.recordItemsWithdrawn(player, withdrawn);
        }
        events.sendWithdrawMessage(player, result, template);
        events.scheduleRefresh(terminal, terminal::updateInventory);

        return result;
    }

    public AbsorbResult absorbFromInventory(Inventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType().isAir()) {
            return new AbsorbResult(0, 0);
        }

        int originalAmount = item.getAmount();
        ItemStack remaining = network.addToNetwork(item.clone());
        int leftover = remaining == null ? 0 : remaining.getAmount();
        int absorbed = originalAmount - leftover;

        if (leftover == 0) {
            inventory.setItem(slot, null);
        } else {
            inventory.setItem(slot, remaining);
        }

        return new AbsorbResult(absorbed, leftover);
    }
}
