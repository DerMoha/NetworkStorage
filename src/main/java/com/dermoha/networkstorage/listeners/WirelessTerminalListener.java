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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WirelessTerminalListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private static final Pattern USES_PATTERN = Pattern.compile("([0-9]+) / ([0-9]+)");

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

            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) {
                return;
            }

            List<String> lore = meta.getLore();
            int usesLineIndex = -1;
            int currentUses = -1;
            int maxUses = -1;

            for (int i = 0; i < lore.size(); i++) {
                Matcher matcher = USES_PATTERN.matcher(lore.get(i));
                if (matcher.find()) {
                    usesLineIndex = i;
                    currentUses = Integer.parseInt(matcher.group(1));
                    maxUses = Integer.parseInt(matcher.group(2));
                    break;
                }
            }

            if (currentUses == 0) {
                player.sendMessage(lang.get("wireless_terminal.broken"));
                return;
            }

            Network network = plugin.getNetworkManager().getPlayerNetwork(player);
            if (network == null) {
                player.sendMessage(lang.get("no_network"));
                return;
            }

            if (!network.canAccess(player)) {
                player.sendMessage(lang.get("trust.no_permission_access"));
                return;
            }

            if (currentUses > 0 && usesLineIndex != -1) {
                currentUses--;
                lore.set(usesLineIndex, lang.get("wireless_terminal.lore.durability", String.valueOf(currentUses), String.valueOf(maxUses)));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }

            TerminalGUI gui = new TerminalGUI(player, network, plugin);
            plugin.getChestInteractListener().addOpenTerminal(player.getUniqueId(), gui);
            gui.open();
        }
    }

    public static ItemStack createWirelessTerminal(NetworkStoragePlugin plugin) {
        LanguageManager lang = plugin.getLanguageManager();
        int durability = plugin.getConfigManager().getWirelessTerminalDurability();

        ItemStack terminal = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = terminal.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.get("wireless_terminal.name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.get("wireless_terminal.lore1"));
            lore.add(lang.get("wireless_terminal.lore2"));
            lore.add(lang.get("wireless_terminal.lore.durability", String.valueOf(durability), String.valueOf(durability)));
            meta.setLore(lore);
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
