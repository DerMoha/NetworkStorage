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

public class WirelessNetworkSelectGUI implements InventoryHolder {

    private final Player player;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Inventory inventory;
    private final EquipmentSlot hand;
    private final Map<Integer, String> slotToNetwork = new HashMap<>();

    public WirelessNetworkSelectGUI(Player player, List<Network> networks, NetworkStoragePlugin plugin, EquipmentSlot hand) {
        this.player = player;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.hand = hand;
        int inventorySize = Math.max(9, Math.min(54, ((networks.size() - 1) / 9 + 1) * 9));
        this.inventory = Bukkit.createInventory(this, inventorySize, lang.getMessage("wireless.select.title"));

        populateInventory(networks);
    }

    private void populateInventory(List<Network> networks) {
        String selectedWirelessName = plugin.getNetworkManager().getSelectedWirelessNetworkName(player);

        for (int i = 0; i < Math.min(networks.size(), inventory.getSize()); i++) {
            Network network = networks.get(i);
            ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);
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

    public void handleClick(int slot) {
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
