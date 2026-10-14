package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WirelessNetworkSelectGUI implements PluginInventory {

    private final Player player;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Inventory inventory;
    private final EquipmentSlot hand;
    private int page;
    private final Map<Integer, String> slotToNetwork = new HashMap<>();

    public WirelessNetworkSelectGUI(Player player, List<Network> networks, NetworkStoragePlugin plugin, EquipmentSlot hand) {
        this.player = player;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.hand = hand;
        int inventorySize = 54;
        this.inventory = Bukkit.createInventory(this, inventorySize, lang.getMessage("wireless.select.title"));

        populateInventory(networks);
    }

    private void populateInventory(List<Network> networks) {
        networks = networks.stream().sorted(java.util.Comparator.comparing(Network::getName)).toList();
        SelectionPage<Network> selection = SelectionPage.of(networks, page);
        page = selection.index();
        inventory.clear();
        slotToNetwork.clear();
        if (selection.hasPrevious()) inventory.setItem(45, navigation("§ePrevious page"));
        if (selection.hasNext()) inventory.setItem(53, navigation("§eNext page"));
        String selectedWirelessName = plugin.getNetworkManager().getSelectedWirelessNetworkName(player);

        for (int i = 0; i < selection.entries().size(); i++) {
            Network network = selection.entries().get(i);
            ItemStack item = new ItemStack(plugin.getConfigManager().getWirelessTerminalMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(String.format(lang.getMessage("wireless.select.item"), network.getName()));

                List<String> lore = new ArrayList<>();
                lore.add(String.format(lang.getMessage("wireless.select.owner"), plugin.getNetworkManager().getNetworkOwnerName(network)));
                if (network.getOwner().equals(player.getUniqueId())) {
                    lore.add(lang.getMessage("wireless.select.access_owned"));
                } else {
                    lore.add(lang.getMessage("wireless.select.access_trusted"));
                }
                if (network.getName().equals(selectedWirelessName)) {
                    lore.add(lang.getMessage("wireless.select.last_used"));
                }
                lore.add(lang.getMessage("wireless.select.click"));

                meta.setLore(lore);
                ItemUtils.applyCustomModelData(meta, plugin.getConfigManager().getOptionalCustomModelData("custom-model-data.gui.wireless-select.item"));
                item.setItemMeta(meta);
            }

            inventory.setItem(i, item);
            slotToNetwork.put(i, network.getName());
        }
    }

    private ItemStack navigation(String label) {
        ItemStack item = new ItemStack(org.bukkit.Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(label);
        item.setItemMeta(meta);
        return item;
    }

    public void handleClick(int slot) {
        if (slot == 45 || slot == 53) {
            if (inventory.getItem(slot) != null) page += slot == 45 ? -1 : 1;
            populateInventory(plugin.getNetworkManager().getAccessibleNetworks(player));
            return;
        }
        String networkName = slotToNetwork.get(slot);
        if (networkName == null) {
            return;
        }

        player.closeInventory();
        plugin.getWirelessTerminalListener().openSelectedNetwork(player, hand, networkName);
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
