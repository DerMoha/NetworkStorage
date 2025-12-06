package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TrustManagementGUI implements InventoryHolder {

    private final Player player;
    private final Network network;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final NetworkConfigGUI parentGUI;
    private Inventory inventory;
    private final List<UUID> trustedPlayers;

    private static final int GUI_SIZE = 54;
    private static final int BACK_SLOT = 49;

    public TrustManagementGUI(Player player, Network network, NetworkStoragePlugin plugin, NetworkConfigGUI parentGUI) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.parentGUI = parentGUI;
        this.trustedPlayers = new ArrayList<>(network.getTrustedPlayers());
        this.inventory = Bukkit.createInventory(this, GUI_SIZE,
            lang.getMessage("network.trust.title").replace("%s", network.getName()));
        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        // Display trusted players
        for (int i = 0; i < trustedPlayers.size() && i < 45; i++) {
            UUID trustedUUID = trustedPlayers.get(i);
            OfflinePlayer trustedPlayer = Bukkit.getOfflinePlayer(trustedUUID);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(trustedPlayer);
            meta.setDisplayName("§e" + trustedPlayer.getName());

            List<String> lore = new ArrayList<>();
            lore.add("§7Click to untrust this player");
            meta.setLore(lore);

            head.setItemMeta(meta);
            inventory.setItem(i, head);
        }

        // Back button
        ItemStack backButton = new ItemStack(Material.ARROW);
        org.bukkit.inventory.meta.ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(lang.getMessage("network.trust.back"));
        backButton.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, backButton);
    }

    public void handleClick(int slot) {
        if (slot == BACK_SLOT) {
            // Go back to config GUI or network selector
            if (parentGUI != null) {
                parentGUI.updateInventory();
                parentGUI.open();
            } else {
                NetworkSelectorGUI selectorGUI = new NetworkSelectorGUI(player, plugin);
                selectorGUI.open();
            }
            return;
        }

        if (slot >= 0 && slot < trustedPlayers.size()) {
            // Untrust player
            UUID trustedUUID = trustedPlayers.get(slot);
            OfflinePlayer trustedPlayer = Bukkit.getOfflinePlayer(trustedUUID);

            network.removeTrustedPlayer(trustedUUID);
            player.sendMessage("§a" + trustedPlayer.getName() + " has been untrusted.");

            // Refresh GUI
            trustedPlayers.remove(slot);
            updateInventory();
        }
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
