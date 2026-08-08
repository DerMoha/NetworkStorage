package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Prevents the non-tool hand from performing a second right-click interaction
 * while a storage tool is being used in the other hand.
 */
public class StorageToolInteractionListener implements Listener {

    private final NetworkStoragePlugin plugin;

    public StorageToolInteractionListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        EquipmentSlot hand = event.getHand();
        if (hand == null || isStorageTool(event.getItem())) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack otherHandItem = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();

        if (isStorageTool(otherHandItem)) {
            event.setCancelled(true);
        }
    }

    private boolean isStorageTool(ItemStack item) {
        return WandListener.isStorageWand(item, plugin)
                || WirelessTerminalListener.isWirelessTerminal(item, plugin);
    }
}
