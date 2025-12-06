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

import java.util.ArrayList;
import java.util.List;

public class NetworkConfigGUI implements InventoryHolder {

    private final Player player;
    private final Network network;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private Inventory inventory;

    private static final int GUI_SIZE = 27;
    private static final int RENAME_SLOT = 10;
    private static final int ICON_SLOT = 11;
    private static final int TRUST_SLOT = 12;
    private static final int STATS_SLOT = 13;
    private static final int DELETE_SLOT = 15;
    private static final int BACK_SLOT = 22;

    public NetworkConfigGUI(Player player, Network network, NetworkStoragePlugin plugin) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.inventory = Bukkit.createInventory(this, GUI_SIZE,
            lang.getMessage("network.config.title").replace("%s", network.getName()));
        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        // Rename Network
        ItemStack renameItem = new ItemStack(Material.NAME_TAG);
        ItemMeta renameMeta = renameItem.getItemMeta();
        renameMeta.setDisplayName(lang.getMessage("network.config.rename"));
        renameMeta.setLore(List.of(lang.getMessage("network.config.rename_lore")));
        renameItem.setItemMeta(renameMeta);
        inventory.setItem(RENAME_SLOT, renameItem);

        // Change Icon
        ItemStack iconItem = new ItemStack(network.getIconMaterial());
        ItemMeta iconMeta = iconItem.getItemMeta();
        iconMeta.setDisplayName(lang.getMessage("network.config.icon"));
        iconMeta.setLore(List.of(
            lang.getMessage("network.config.icon_lore1"),
            lang.getMessage("network.config.icon_lore2")
        ));
        iconItem.setItemMeta(iconMeta);
        inventory.setItem(ICON_SLOT, iconItem);

        // Manage Trust (only if trust system enabled)
        if (plugin.getConfigManager().isTrustSystemEnabled()) {
            ItemStack trustItem = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta trustMeta = trustItem.getItemMeta();
            trustMeta.setDisplayName(lang.getMessage("network.config.trust"));

            List<String> trustLore = new ArrayList<>();
            trustLore.add(lang.getMessage("network.config.trust_lore"));
            trustLore.add(lang.getMessage("network.config.trust_count")
                .replace("%s", String.valueOf(network.getTrustedPlayers().size())));
            trustMeta.setLore(trustLore);

            trustItem.setItemMeta(trustMeta);
            inventory.setItem(TRUST_SLOT, trustItem);
        }

        // View Statistics
        ItemStack statsItem = new ItemStack(Material.EMERALD);
        ItemMeta statsMeta = statsItem.getItemMeta();
        statsMeta.setDisplayName(lang.getMessage("network.config.stats"));
        statsMeta.setLore(List.of(lang.getMessage("network.config.stats_lore")));
        statsItem.setItemMeta(statsMeta);
        inventory.setItem(STATS_SLOT, statsItem);

        // Delete Network
        ItemStack deleteItem = new ItemStack(Material.BARRIER);
        ItemMeta deleteMeta = deleteItem.getItemMeta();
        deleteMeta.setDisplayName(lang.getMessage("network.config.delete"));
        deleteMeta.setLore(List.of(
            lang.getMessage("network.config.delete_lore1"),
            lang.getMessage("network.config.delete_lore2")
        ));
        deleteItem.setItemMeta(deleteMeta);
        inventory.setItem(DELETE_SLOT, deleteItem);

        // Back button
        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(lang.getMessage("network.config.back"));
        backButton.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, backButton);
    }

    public void handleClick(int slot) {
        switch (slot) {
            case RENAME_SLOT:
                handleRename();
                break;
            case ICON_SLOT:
                handleChangeIcon();
                break;
            case TRUST_SLOT:
                if (plugin.getConfigManager().isTrustSystemEnabled()) {
                    handleManageTrust();
                }
                break;
            case STATS_SLOT:
                handleViewStats();
                break;
            case DELETE_SLOT:
                handleDelete();
                break;
            case BACK_SLOT:
                handleBack();
                break;
        }
    }

    private void handleRename() {
        player.closeInventory();
        player.sendMessage(lang.getMessage("network.rename.prompt"));
        player.sendMessage(lang.getMessage("network.rename.cancel_hint"));
        plugin.getSearchManager().startRenamingNetwork(player, network.getName());
    }

    private void handleChangeIcon() {
        IconPickerGUI iconPicker = new IconPickerGUI(player, network, plugin, this);
        iconPicker.open();
    }

    private void handleManageTrust() {
        TrustManagementGUI trustGUI = new TrustManagementGUI(player, network, plugin, this);
        trustGUI.open();
    }

    private void handleViewStats() {
        plugin.getChestInteractListener().setTransitioningToStats(player.getUniqueId());
        new StatsGUI(player, network, plugin, null).open();
    }

    private void handleDelete() {
        List<Network> playerNetworks = plugin.getNetworkManager().getPlayerNetworks(player);
        if (playerNetworks.size() <= 1) {
            player.sendMessage(lang.getMessage("network.cannot_delete_last"));
            return;
        }

        player.closeInventory();
        player.sendMessage(lang.getMessage("network.delete.confirm1").replace("%s", network.getName()));
        player.sendMessage(lang.getMessage("network.delete.confirm2")
            .replace("%chests%", String.valueOf(network.getChestLocations().size()))
            .replace("%terminals%", String.valueOf(network.getTerminalLocations().size())));
        player.sendMessage(lang.getMessage("network.delete.confirm3").replace("%s", network.getName()));

        plugin.getSearchManager().startDeletingNetwork(player, network.getName());
    }

    private void handleBack() {
        NetworkSelectorGUI selectorGUI = new NetworkSelectorGUI(player, plugin);
        selectorGUI.open();
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Network getNetwork() {
        return network;
    }
}
