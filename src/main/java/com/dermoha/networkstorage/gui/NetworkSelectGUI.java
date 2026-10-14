package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkSelectGUI implements PluginInventory {

    private final Player player;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Inventory inventory;
    private int page;
    private final Map<Integer, String> slotToNetwork = new HashMap<>();

    public NetworkSelectGUI(Player player, List<Network> networks, NetworkStoragePlugin plugin) {
        this.player = player;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        int inventorySize = 54;
        this.inventory = Bukkit.createInventory(this, inventorySize, lang.getMessage("network.select.title"));

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
        String activeNetworkName = null;
        Network activeNetwork = plugin.getNetworkManager().getPlayerNetwork(player);
        if (activeNetwork != null) {
            activeNetworkName = activeNetwork.getName();
        }

        for (int i = 0; i < selection.entries().size(); i++) {
            Network network = selection.entries().get(i);
            ItemStack item = new ItemStack(Material.CHEST);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(String.format(lang.getMessage("network.select.item"), network.getName()));

                List<String> lore = new ArrayList<>();
                lore.add(String.format(lang.getMessage("network.select.chests"), network.getChestLocations().size()));
                lore.add(String.format(lang.getMessage("network.select.terminals"), network.getTerminalLocations().size()));
                if (network.getName().equals(activeNetworkName)) {
                    lore.add(lang.getMessage("network.select.active"));
                }
                lore.add(lang.getMessage("network.select.click"));

                meta.setLore(lore);
                ItemUtils.applyCustomModelData(meta, plugin.getConfigManager().getOptionalCustomModelData("custom-model-data.gui.network-select.item"));
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
            populateInventory(plugin.getNetworkManager().getOwnedNetworks(player));
            return;
        }
        String networkName = slotToNetwork.get(slot);
        if (networkName == null) {
            return;
        }

        if (plugin.getNetworkManager().selectPlayerNetwork(player, networkName)) {
            player.sendMessage(String.format(lang.getMessage("network.select.success"), networkName));
        } else {
            player.sendMessage(String.format(lang.getMessage("network.select.not_found"), networkName));
        }
        player.closeInventory();
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
