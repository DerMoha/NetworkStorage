package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class WirelessTerminalListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    public WirelessTerminalListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || !isWirelessTerminal(item, lang)) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);

            Network network = plugin.getNetworkManager().getPlayerNetwork(player);
            if (network == null) {
                player.sendMessage(lang.get("no_network"));
                return;
            }

            if (!network.canAccess(player)) {
                player.sendMessage(lang.get("trust.no_permission_access"));
                return;
            }

            new TerminalGUI(player, network, plugin).open();
        }
    }

    public static ItemStack createWirelessTerminal(LanguageManager lang) {
        ItemStack terminal = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = terminal.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.get("wireless_terminal.name"));
            meta.setLore(Arrays.asList(
                    lang.get("wireless_terminal.lore1"),
                    lang.get("wireless_terminal.lore2")
            ));
            terminal.setItemMeta(meta);
        }
        return terminal;
    }

    public static boolean isWirelessTerminal(ItemStack item, LanguageManager lang) {
        if (item == null || item.getType() != Material.RECOVERY_COMPASS) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(lang.get("wireless_terminal.name"));
    }
}
