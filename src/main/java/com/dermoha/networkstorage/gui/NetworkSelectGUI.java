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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkSelectGUI implements InventoryHolder {

    private final Player player;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Inventory inventory;
    private final Map<Integer, String> slotToNetwork = new HashMap<>();

    public NetworkSelectGUI(Player player, List<Network> networks, NetworkStoragePlugin plugin) {
        this.player = player;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        int inventorySize = Math.max(9, Math.min(54, ((networks.size() - 1) / 9 + 1) * 9));
        this.inventory = Bukkit.createInventory(this, inventorySize, lang.getMessage("network.select.title"));

        populateInventory(networks);
    }

    private void populateInventory(List<Network> networks) {
        String activeNetworkName = null;
        Network activeNetwork = plugin.getNetworkManager().getPlayerNetwork(player);
        if (activeNetwork != null) {
            activeNetworkName = activeNetwork.getName();
        }

        for (int i = 0; i < Math.min(networks.size(), inventory.getSize()); i++) {
            Network network = networks.get(i);
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
                item.setItemMeta(meta);
            }

            inventory.setItem(i, item);
            slotToNetwork.put(i, network.getName());
        }
    }

    public void handleClick(int slot) {
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
