package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.gui.WirelessNetworkSelectGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WirelessTerminalListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private static final Pattern USES_PATTERN = Pattern.compile("([0-9]+) / ([0-9]+)");

    public WirelessTerminalListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        LanguageManager lang = plugin.getLanguageManager();

        if (!isWirelessTerminal(item, lang)) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);

            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) {
                return;
            }

            if (meta.getLore() == null) {
                return;
            }

            List<Network> accessibleNetworks = plugin.getNetworkManager().getAccessibleNetworks(player);
            if (accessibleNetworks.isEmpty()) {
                player.sendMessage(lang.getMessage("no_network"));
                return;
            }

            Network rememberedNetwork = plugin.getNetworkManager().getSelectedWirelessNetwork(player);
            if (rememberedNetwork != null) {
                openSelectedNetwork(player, event.getHand(), rememberedNetwork.getName());
                return;
            }

            if (accessibleNetworks.size() == 1) {
                openSelectedNetwork(player, event.getHand(), accessibleNetworks.get(0).getName());
                return;
            }

            new WirelessNetworkSelectGUI(player, accessibleNetworks, plugin, event.getHand()).open();
        }
    }

    public void openSelectedNetwork(Player player, EquipmentSlot hand, String networkName) {
        LanguageManager lang = plugin.getLanguageManager();
        Network network = plugin.getNetworkManager().findAccessibleNetwork(player, networkName);
        if (network == null) {
            player.sendMessage(String.format(lang.getMessage("wireless.select.not_found"), networkName));
            return;
        }

        ItemStack item = getWirelessTerminalInHand(player, hand);
        if (!isWirelessTerminal(item, lang)) {
            player.sendMessage(lang.getMessage("wireless.select.item_missing"));
            return;
        }

        WirelessUseState useState = getUseState(item, lang);
        if (useState.currentUses == 0) {
            player.sendMessage(lang.getMessage("wireless_terminal.broken"));
            return;
        }

        if (useState.usesLineIndex != -1 && useState.currentUses > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) {
                player.sendMessage(lang.getMessage("wireless_terminal.broken"));
                return;
            }

            List<String> lore = new ArrayList<>(meta.getLore());
            lore.set(useState.usesLineIndex, String.format(lang.getMessage("wireless_terminal.lore.durability"), useState.currentUses - 1, useState.maxUses));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        plugin.getNetworkManager().selectWirelessNetwork(player, network.getName());

        TerminalGUI gui = new TerminalGUI(player, network, plugin);
        plugin.getChestInteractListener().addOpenTerminal(player.getUniqueId(), gui);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        gui.open();
    }

    private ItemStack getWirelessTerminalInHand(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }

    private WirelessUseState getUseState(ItemStack item, LanguageManager lang) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return new WirelessUseState(-1, 0, 0);
        }

        List<String> lore = meta.getLore();
        for (int i = 0; i < lore.size(); i++) {
            Matcher matcher = USES_PATTERN.matcher(lore.get(i));
            if (!matcher.find()) {
                continue;
            }

            try {
                int currentUses = Integer.parseInt(matcher.group(1));
                int maxUses = Integer.parseInt(matcher.group(2));
                return new WirelessUseState(i, currentUses, maxUses);
            } catch (NumberFormatException e) {
                return new WirelessUseState(-1, 0, 0);
            }
        }

        int defaultUses = plugin.getConfigManager().getWirelessTerminalDurability();
        if (meta != null) {
            List<String> loreWithDurability = new ArrayList<>(meta.getLore());
            loreWithDurability.add(String.format(lang.getMessage("wireless_terminal.lore.durability"), defaultUses, defaultUses));
            meta.setLore(loreWithDurability);
            item.setItemMeta(meta);
        }
        return new WirelessUseState(meta.getLore() == null ? -1 : meta.getLore().size() - 1, defaultUses, defaultUses);
    }

    private record WirelessUseState(int usesLineIndex, int currentUses, int maxUses) {
    }

    public static ItemStack createWirelessTerminal(NetworkStoragePlugin plugin) {
        LanguageManager lang = plugin.getLanguageManager();
        int durability = plugin.getConfigManager().getWirelessTerminalDurability();

        ItemStack terminal = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = terminal.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("wireless_terminal.name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("wireless_terminal.lore1"));
            lore.add(lang.getMessage("wireless_terminal.lore2"));
            lore.add(String.format(lang.getMessage("wireless_terminal.lore.durability"), durability, durability));
            meta.setLore(lore);
            ItemUtils.applyCustomModelData(meta, plugin.getConfigManager().getWirelessTerminalCustomModelData());
            terminal.setItemMeta(meta);
        }
        return terminal;
    }

    public static boolean isWirelessTerminal(ItemStack item, LanguageManager lang) {
        if (item == null || item.getType() != Material.RECOVERY_COMPASS) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(lang.getMessage("wireless_terminal.name"));
    }
}
