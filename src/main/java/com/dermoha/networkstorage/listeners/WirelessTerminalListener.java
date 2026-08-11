package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.TerminalGUI;
import com.dermoha.networkstorage.gui.WirelessNetworkSelectGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WirelessTerminalListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final Map<UUID, FinalUseConfirmation> finalUseConfirmations = new HashMap<>();
    private static final Pattern USES_PATTERN = Pattern.compile("([0-9]+) / ([0-9]+)");
    private static final long FINAL_USE_CONFIRMATION_TIMEOUT_MILLIS = 30_000L;

    public WirelessTerminalListener(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        LanguageManager lang = plugin.getLanguageManager();

        if (!isWirelessTerminal(item, plugin)) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);

            if (!plugin.getConfigManager().hasPermission(player, "networkstorage.wireless")) {
                player.sendMessage(lang.getMessage("no_permission_wireless"));
                return;
            }

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

            if (!ensureFinalUseConfirmation(player, event.getHand(), item, lang)) {
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
        if (!plugin.getConfigManager().hasPermission(player, "networkstorage.wireless")) {
            player.sendMessage(lang.getMessage("no_permission_wireless"));
            return;
        }

        Network network = plugin.getNetworkManager().findAccessibleNetwork(player, networkName);
        if (network == null) {
            player.sendMessage(String.format(lang.getMessage("wireless.select.not_found"), networkName));
            return;
        }

        ItemStack item = getWirelessTerminalInHand(player, hand);
        if (!isWirelessTerminal(item, plugin)) {
            player.sendMessage(lang.getMessage("wireless.select.item_missing"));
            return;
        }

        WirelessUseState useState = getUseState(item, lang);
        if (useState.currentUses == 0) {
            if (plugin.getConfigManager().isWirelessTerminalBreakOnZeroEnabled()) {
                if (breakWirelessTerminal(player, hand, item)) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                }
            }
            clearFinalUseConfirmation(player);
            player.sendMessage(lang.getMessage("wireless_terminal.broken"));
            return;
        }

        boolean breaksOnZero = plugin.getConfigManager().isWirelessTerminalBreakOnZeroEnabled();
        boolean isLastUse = useState.currentUses == 1;
        if (isLastUse && breaksOnZero && !hasFinalUseConfirmation(player, hand, item)) {
            player.sendMessage(lang.getMessage("wireless_terminal.last_use"));
            return;
        }

        TerminalGUI gui = new TerminalGUI(player, network, plugin);
        if (!plugin.getTerminalSessions().openTerminal(player, gui)) {
            return;
        }

        plugin.getNetworkManager().selectWirelessNetwork(player, network.getName());

        setUseState(item, useState.currentUses - 1, useState.maxUses, lang);
        if (isLastUse && breaksOnZero) {
            clearFinalUseConfirmation(player);
            plugin.getTerminalSessions().scheduleWirelessTerminalBreak(player, hand, item);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    private boolean ensureFinalUseConfirmation(Player player,
                                               EquipmentSlot hand,
                                               ItemStack item,
                                               LanguageManager lang) {
        if (!plugin.getConfigManager().isWirelessTerminalBreakOnZeroEnabled()) {
            clearFinalUseConfirmation(player);
            return true;
        }

        WirelessUseState useState = getUseState(item, lang);
        if (useState.currentUses != 1) {
            clearFinalUseConfirmation(player);
            return true;
        }

        if (hasFinalUseConfirmation(player, hand, item)) {
            return true;
        }

        finalUseConfirmations.put(player.getUniqueId(), new FinalUseConfirmation(
                hand,
                item.clone(),
                System.currentTimeMillis() + FINAL_USE_CONFIRMATION_TIMEOUT_MILLIS));
        player.sendMessage(lang.getMessage("wireless_terminal.last_use"));
        return false;
    }

    private boolean hasFinalUseConfirmation(Player player, EquipmentSlot hand, ItemStack item) {
        FinalUseConfirmation confirmation = finalUseConfirmations.get(player.getUniqueId());
        if (confirmation == null) {
            return false;
        }

        if (confirmation.expiresAtMillis < System.currentTimeMillis()
                || confirmation.hand != hand
                || !confirmation.item.isSimilar(item)) {
            clearFinalUseConfirmation(player);
            return false;
        }
        return true;
    }

    private void clearFinalUseConfirmation(Player player) {
        finalUseConfirmations.remove(player.getUniqueId());
    }

    public boolean breakWirelessTerminal(Player player, EquipmentSlot hand, ItemStack expectedItem) {
        ItemStack heldItem = getWirelessTerminalInHand(player, hand);
        if (isMatchingWirelessTerminal(heldItem, expectedItem)) {
            setWirelessTerminalSlot(player, hand, new ItemStack(Material.AIR));
            return true;
        }

        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            if (isMatchingWirelessTerminal(player.getInventory().getItem(slot), expectedItem)) {
                player.getInventory().setItem(slot, new ItemStack(Material.AIR));
                return true;
            }
        }
        return false;
    }

    private boolean isMatchingWirelessTerminal(ItemStack item, ItemStack expectedItem) {
        return isWirelessTerminal(item, plugin) && item.isSimilar(expectedItem);
    }

    private void setWirelessTerminalSlot(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private ItemStack getWirelessTerminalInHand(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }

    private WirelessUseState getUseState(ItemStack item, LanguageManager lang) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new WirelessUseState(0, 0);
        }

        PersistentDataContainer data = meta.getPersistentDataContainer();
        Integer storedCurrentUses = data.get(getWirelessTerminalUsesKey(plugin), PersistentDataType.INTEGER);
        Integer storedMaxUses = data.get(getWirelessTerminalMaxUsesKey(plugin), PersistentDataType.INTEGER);
        if (storedCurrentUses != null && storedMaxUses != null) {
            int maxUses = Math.max(1, storedMaxUses);
            int currentUses = Math.max(0, Math.min(storedCurrentUses, maxUses));
            if (currentUses != storedCurrentUses || maxUses != storedMaxUses || !hasDurabilityLore(meta)) {
                setUseState(item, currentUses, maxUses, lang);
            }
            return new WirelessUseState(currentUses, maxUses);
        }

        WirelessUseState legacyUseState = getLegacyLoreUseState(meta);
        if (legacyUseState.maxUses > 0) {
            setUseState(item, legacyUseState.currentUses, legacyUseState.maxUses, lang);
            return legacyUseState;
        }

        int defaultUses = plugin.getConfigManager().getWirelessTerminalDurability();
        setUseState(item, defaultUses, defaultUses, lang);
        return new WirelessUseState(defaultUses, defaultUses);
    }

    private WirelessUseState getLegacyLoreUseState(ItemMeta meta) {
        if (!meta.hasLore() || meta.getLore() == null) {
            return new WirelessUseState(0, 0);
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
                maxUses = Math.max(1, maxUses);
                return new WirelessUseState(Math.max(0, Math.min(currentUses, maxUses)), maxUses);
            } catch (NumberFormatException e) {
                return new WirelessUseState(0, 0);
            }
        }

        return new WirelessUseState(0, 0);
    }

    private void setUseState(ItemStack item, int currentUses, int maxUses, LanguageManager lang) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        int safeMaxUses = Math.max(1, maxUses);
        int safeCurrentUses = Math.max(0, Math.min(currentUses, safeMaxUses));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(getWirelessTerminalUsesKey(plugin), PersistentDataType.INTEGER, safeCurrentUses);
        data.set(getWirelessTerminalMaxUsesKey(plugin), PersistentDataType.INTEGER, safeMaxUses);

        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        String durabilityLine = String.format(lang.getMessage("wireless_terminal.lore.durability"), safeCurrentUses, safeMaxUses);
        boolean replaced = false;
        for (int i = 0; i < lore.size(); i++) {
            if (USES_PATTERN.matcher(lore.get(i)).find()) {
                lore.set(i, durabilityLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lore.add(durabilityLine);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private boolean hasDurabilityLore(ItemMeta meta) {
        if (meta.getLore() == null) {
            return false;
        }
        for (String line : meta.getLore()) {
            if (USES_PATTERN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private record WirelessUseState(int currentUses, int maxUses) {
    }

    private record FinalUseConfirmation(EquipmentSlot hand, ItemStack item, long expiresAtMillis) {
    }

    public static ItemStack createWirelessTerminal(NetworkStoragePlugin plugin) {
        LanguageManager lang = plugin.getLanguageManager();
        int durability = plugin.getConfigManager().getWirelessTerminalDurability();

        ItemStack terminal = new ItemStack(plugin.getConfigManager().getWirelessTerminalMaterial());
        ItemMeta meta = terminal.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("wireless_terminal.name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("wireless_terminal.lore1"));
            lore.add(lang.getMessage("wireless_terminal.lore2"));
            lore.add(String.format(lang.getMessage("wireless_terminal.lore.durability"), durability, durability));
            meta.setLore(lore);
            ItemUtils.applyCustomModelData(meta, plugin.getConfigManager().getWirelessTerminalCustomModelData());
            meta.getPersistentDataContainer().set(getWirelessTerminalKey(plugin), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(getWirelessTerminalUsesKey(plugin), PersistentDataType.INTEGER, durability);
            meta.getPersistentDataContainer().set(getWirelessTerminalMaxUsesKey(plugin), PersistentDataType.INTEGER, durability);
            terminal.setItemMeta(meta);
        }
        return terminal;
    }

    public static boolean isWirelessTerminal(ItemStack item, NetworkStoragePlugin plugin) {
        if (item == null || item.getType() != plugin.getConfigManager().getWirelessTerminalMaterial()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(getWirelessTerminalKey(plugin), PersistentDataType.BYTE);
    }

    private static NamespacedKey getWirelessTerminalKey(NetworkStoragePlugin plugin) {
        return new NamespacedKey(plugin, "wireless_terminal");
    }

    private static NamespacedKey getWirelessTerminalUsesKey(NetworkStoragePlugin plugin) {
        return new NamespacedKey(plugin, "wireless_terminal_uses");
    }

    private static NamespacedKey getWirelessTerminalMaxUsesKey(NetworkStoragePlugin plugin) {
        return new NamespacedKey(plugin, "wireless_terminal_max_uses");
    }
}
