package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class IconPickerGUI implements InventoryHolder {

    private final Player player;
    private final Network network;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final NetworkConfigGUI parentGUI;
    private Inventory inventory;

    private static final int GUI_SIZE = 27;
    private static final int CURRENT_ICON_SLOT = 11;
    private static final int CONFIRM_SLOT = 13;
    private static final int BACK_SLOT = 15;

    public IconPickerGUI(Player player, Network network, NetworkStoragePlugin plugin, NetworkConfigGUI parentGUI) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.parentGUI = parentGUI;
        this.inventory = Bukkit.createInventory(this, GUI_SIZE,
            lang.getMessage("network.icon.title").replace("%s", network.getName()));
        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        // Show current icon
        ItemStack currentIcon = new ItemStack(network.getIconMaterial());
        ItemMeta currentMeta = currentIcon.getItemMeta();
        currentMeta.setDisplayName(lang.getMessage("network.icon.current"));
        currentMeta.setLore(List.of(lang.getMessage("network.icon.current_lore")));
        currentIcon.setItemMeta(currentMeta);
        inventory.setItem(CURRENT_ICON_SLOT, currentIcon);

        // Confirm button (uses item in hand)
        ItemStack confirmButton = new ItemStack(Material.LIME_DYE);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(lang.getMessage("network.icon.confirm"));
        confirmMeta.setLore(List.of(
            lang.getMessage("network.icon.confirm_lore1"),
            lang.getMessage("network.icon.confirm_lore2"),
            "",
            lang.getMessage("network.icon.confirm_lore3")
        ));
        confirmButton.setItemMeta(confirmMeta);
        inventory.setItem(CONFIRM_SLOT, confirmButton);

        // Back button
        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(lang.getMessage("network.icon.back"));
        backButton.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, backButton);
    }

    public void handleClick(int slot) {
        if (slot == CONFIRM_SLOT) {
            handleConfirm();
        } else if (slot == BACK_SLOT) {
            handleBack();
        }
    }

    private void handleConfirm() {
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            player.sendMessage(lang.getMessage("network.icon.no_item"));
            return;
        }

        Material newIcon = itemInHand.getType();
        network.setIconMaterial(newIcon);
        player.sendMessage(lang.getMessage("network.icon.changed")
            .replace("%s", formatMaterialName(newIcon)));

        // Return to config GUI
        handleBack();
    }

    private void handleBack() {
        if (parentGUI != null) {
            parentGUI.updateInventory();
            parentGUI.open();
        } else {
            NetworkSelectorGUI selectorGUI = new NetworkSelectorGUI(player, plugin);
            selectorGUI.open();
        }
    }

    private String formatMaterialName(Material material) {
        String name = material.name().replace('_', ' ');
        String[] words = name.split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
                result.append(" ");
            }
        }

        return result.toString().trim();
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
