package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigEditorGUI implements InventoryHolder {

    private final NetworkStoragePlugin plugin;
    private final ConfigManager configManager;
    private final Inventory inventory;

    private static final int SLOT_HOPS = 10;
    private static final int SLOT_COMPARATOR = 11;
    private static final int SLOT_TRUST = 12;
    private static final int SLOT_PROTECT = 13;
    private static final int SLOT_WIRELESS = 14;
    private static final int SLOT_AUTO_SAVE = 15;
    private static final int SLOT_SENDER_INTERVAL = 16;
    private static final int SLOT_MAX_CHESTS = 19;
    private static final int SLOT_MAX_TERMINALS = 20;
    private static final int SLOT_MAX_SENDERS = 21;
    private static final int SLOT_CLOSE = 49;

    private final Map<Integer, Runnable> toggleActions = new HashMap<>();
    private final Map<Integer, java.util.function.IntConsumer> intAdjustActions = new HashMap<>();
    private final Map<Integer, String> intDisplayLabels = new HashMap<>();
    private final Map<Integer, java.util.function.Supplier<Integer>> intSuppliers = new HashMap<>();

    public ConfigEditorGUI(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.inventory = Bukkit.createInventory(this, 54, "§8NetworkStorage Config");

        registerActions();
        refresh();
    }

    private void registerActions() {
        toggleActions.put(SLOT_HOPS, () -> toggle("enable-hopper-integration"));
        toggleActions.put(SLOT_COMPARATOR, () -> toggle("enable-comparator-output"));
        toggleActions.put(SLOT_TRUST, () -> toggle("enable-trust-system"));
        toggleActions.put(SLOT_PROTECT, () -> toggle("protect-network-containers"));
        toggleActions.put(SLOT_WIRELESS, () -> toggle("enable-permissions"));

        intAdjustActions.put(SLOT_MAX_CHESTS, delta -> adjustClamped("max-chests-per-network", delta, 1, 10_000));
        intAdjustActions.put(SLOT_MAX_TERMINALS, delta -> adjustClamped("max-terminals-per-network", delta, 1, 10_000));
        intAdjustActions.put(SLOT_MAX_SENDERS, delta -> adjustClamped("max-sender-chests-per-network", delta, 1, 10_000));
        intAdjustActions.put(SLOT_AUTO_SAVE, delta -> adjustClamped("auto-save-interval-minutes", delta, 1, 10_080));
        intAdjustActions.put(SLOT_SENDER_INTERVAL, delta -> adjustClamped("sender-chest-transfer-interval-seconds", delta, 1, 86_400));

        intDisplayLabels.put(SLOT_MAX_CHESTS, "Max Chests");
        intDisplayLabels.put(SLOT_MAX_TERMINALS, "Max Terminals");
        intDisplayLabels.put(SLOT_MAX_SENDERS, "Max Senders");
        intDisplayLabels.put(SLOT_AUTO_SAVE, "Auto-Save (min)");
        intDisplayLabels.put(SLOT_SENDER_INTERVAL, "Sender Interval (s)");

        intSuppliers.put(SLOT_MAX_CHESTS, configManager::getMaxChestsPerNetwork);
        intSuppliers.put(SLOT_MAX_TERMINALS, configManager::getMaxTerminalsPerNetwork);
        intSuppliers.put(SLOT_MAX_SENDERS, configManager::getMaxSenderChestsPerNetwork);
        intSuppliers.put(SLOT_AUTO_SAVE, configManager::getAutoSaveInterval);
        intSuppliers.put(SLOT_SENDER_INTERVAL, configManager::getSenderChestTransferInterval);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot == SLOT_CLOSE) {
            event.getWhoClicked().closeInventory();
            return;
        }
        boolean isLeftClick = event.isLeftClick();
        boolean isRightClick = event.isRightClick();
        if (toggleActions.containsKey(slot) && isLeftClick) {
            event.setCancelled(true);
            toggleActions.get(slot).run();
            refresh();
        } else if (intAdjustActions.containsKey(slot)) {
            event.setCancelled(true);
            int delta = isLeftClick ? 1 : (isRightClick ? -1 : 0);
            if (delta != 0) {
                intAdjustActions.get(slot).accept(delta);
                refresh();
            }
        }
    }

    private void toggle(String path) {
        boolean current = plugin.getConfig().getBoolean(path);
        plugin.getConfig().set(path, !current);
        plugin.saveConfig();
        configManager.reloadConfig();
    }

    private void adjustClamped(String path, int delta, int min, int max) {
        int current = plugin.getConfig().getInt(path);
        int next = Math.max(min, Math.min(max, current + delta));
        plugin.getConfig().set(path, next);
        plugin.saveConfig();
        configManager.reloadConfig();
    }

    private void refresh() {
        inventory.clear();
        placeToggle(SLOT_HOPS, Material.HOPPER, "Hopper Integration", "enable-hopper-integration");
        placeToggle(SLOT_COMPARATOR, Material.COMPARATOR, "Comparator Output", "enable-comparator-output");
        placeToggle(SLOT_TRUST, Material.PLAYER_HEAD, "Trust System", "enable-trust-system");
        placeToggle(SLOT_PROTECT, Material.SHIELD, "Container Protection", "protect-network-containers");
        placeToggle(SLOT_WIRELESS, Material.REDSTONE_TORCH, "Permissions", "enable-permissions");

        placeInt(SLOT_MAX_CHESTS, Material.CHEST, "Max Chests");
        placeInt(SLOT_MAX_TERMINALS, Material.ENDER_CHEST, "Max Terminals");
        placeInt(SLOT_MAX_SENDERS, Material.DISPENSER, "Max Senders");
        placeInt(SLOT_AUTO_SAVE, Material.CLOCK, "Auto-Save (min)");
        placeInt(SLOT_SENDER_INTERVAL, Material.REPEATER, "Sender Interval (s)");

        ItemStack closeButton = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeButton.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§cClose");
            closeButton.setItemMeta(closeMeta);
        }
        inventory.setItem(SLOT_CLOSE, closeButton);
    }

    private void placeToggle(int slot, Material material, String label, String path) {
        boolean value = plugin.getConfig().getBoolean(path);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((value ? "§a" : "§c") + label);
            meta.setLore(Arrays.asList(
                    "§7Current: " + (value ? "§aenabled" : "§cdisabled"),
                    "§7Click to toggle"
            ));
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    private void placeInt(int slot, Material material, String label) {
        int value = intSuppliers.get(slot).get();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e" + label);
            meta.setLore(Arrays.asList(
                    "§7Current: §f" + value,
                    "§7Left-click +1, right-click -1"
            ));
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
