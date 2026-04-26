package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

public class MiningTaxListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public MiningTaxListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (!plugin.getConfigManager().isMiningTaxEnabled()) {
            return;
        }
        if (plugin.getConfigManager().getNetworkMode() != ConfigManager.NetworkMode.GLOBAL) {
            return;
        }

        Player player = event.getPlayer();
        Network network = plugin.getNetworkManager().getNetwork("Global");
        if (network == null) {
            return;
        }

        double rate = plugin.getConfigManager().getMiningTaxRate();
        if (rate <= 0.0) {
            return;
        }

        for (Iterator<Item> iterator = event.getItems().iterator(); iterator.hasNext(); ) {
            Item itemEntity = iterator.next();
            ItemStack drop = itemEntity.getItemStack();
            int amount = drop.getAmount();
            int taxedAmount = calculateTaxedAmount(amount, rate);
            if (taxedAmount <= 0) {
                continue;
            }

            ItemStack taxStack = drop.clone();
            taxStack.setAmount(taxedAmount);

            ItemStack remaining = network.addToNetwork(taxStack);
            int deposited = taxedAmount;
            if (remaining != null && remaining.getAmount() > 0) {
                deposited -= remaining.getAmount();
            }

            if (deposited <= 0) {
                continue;
            }

            int remainingAmount = amount - deposited;
            if (remainingAmount <= 0) {
                iterator.remove();
            } else {
                ItemStack remainingDrop = itemEntity.getItemStack();
                remainingDrop.setAmount(remainingAmount);
                itemEntity.setItemStack(remainingDrop);
            }
            network.recordItemsTaxed(player, deposited);
        }
    }

    private int calculateTaxedAmount(int amount, double rate) {
        int taxed = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < amount; i++) {
            if (random.nextDouble(100.0) < rate) {
                taxed++;
            }
        }
        return taxed;
    }
}
