package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class NetworkSelectorGUI implements InventoryHolder {

    private final Player player;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private Inventory inventory;
    private final List<Network> playerNetworks;

    private static final int GUI_SIZE = 54;
    private static final int CREATE_NEW_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    public NetworkSelectorGUI(Player player, NetworkStoragePlugin plugin) {
        this.player = player;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.playerNetworks = plugin.getNetworkManager().getPlayerNetworks(player);
        this.inventory = Bukkit.createInventory(this, GUI_SIZE, lang.getMessage("network.selector.title"));
        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        Network activeNetwork = plugin.getNetworkManager().getPlayerNetwork(player);

        // Display player's networks
        for (int i = 0; i < playerNetworks.size() && i < 45; i++) {
            Network network = playerNetworks.get(i);
            boolean isActive = activeNetwork != null && activeNetwork.getName().equals(network.getName());

            ItemStack displayItem = createNetworkItem(network, isActive);
            inventory.setItem(i, displayItem);
        }

        // Control buttons
        addControlButtons();
    }

    private ItemStack createNetworkItem(Network network, boolean isActive) {
        // Use network's custom icon, or ender chest if active, or default icon
        Material material = isActive ? Material.ENDER_CHEST : network.getIconMaterial();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String displayName = isActive ?
            lang.getMessage("network.selector.active").replace("%s", network.getName()) :
            network.getName();
        meta.setDisplayName(displayName);

        List<String> lore = new ArrayList<>();
        lore.add(lang.getMessage("network.selector.chests").replace("%s", String.valueOf(network.getChestLocations().size())));
        lore.add(lang.getMessage("network.selector.terminals").replace("%s", String.valueOf(network.getTerminalLocations().size())));
        lore.add(lang.getMessage("network.selector.sender_chests").replace("%s", String.valueOf(network.getSenderChestLocations().size())));

        long totalItems = network.getNetworkItems().values().stream().mapToLong(Integer::longValue).sum();
        lore.add(lang.getMessage("network.selector.items").replace("%s", String.valueOf(totalItems)));

        // Show trusted players if trust system is enabled
        if (plugin.getConfigManager().isTrustSystemEnabled()) {
            int trustedCount = network.getTrustedPlayers().size();
            lore.add(lang.getMessage("network.selector.trusted_players").replace("%s", String.valueOf(trustedCount)));
        }

        lore.add("");
        if (isActive) {
            lore.add(lang.getMessage("network.selector.currently_active"));
        } else {
            lore.add(lang.getMessage("network.selector.click_to_switch"));
        }
        lore.add(lang.getMessage("network.selector.right_click_configure"));

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addControlButtons() {
        // Create new network button
        ItemStack createButton = new ItemStack(Material.EMERALD);
        ItemMeta createMeta = createButton.getItemMeta();
        createMeta.setDisplayName(lang.getMessage("network.selector.create_new"));

        List<String> createLore = new ArrayList<>();
        createLore.add(lang.getMessage("network.selector.create_new_lore"));
        int maxNetworks = plugin.getConfigManager().getMaxNetworksPerPlayer();
        if (maxNetworks > 0) {
            createLore.add(lang.getMessage("network.selector.networks_count")
                .replace("%current%", String.valueOf(playerNetworks.size()))
                .replace("%max%", String.valueOf(maxNetworks)));
        }
        createMeta.setLore(createLore);
        createButton.setItemMeta(createMeta);
        inventory.setItem(CREATE_NEW_SLOT, createButton);

        // Close button
        ItemStack closeButton = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeButton.getItemMeta();
        closeMeta.setDisplayName(lang.getMessage("network.selector.close"));
        closeButton.setItemMeta(closeMeta);
        inventory.setItem(CLOSE_SLOT, closeButton);
    }

    public void handleClick(int slot, ClickType clickType) {
        if (slot < 0 || slot >= playerNetworks.size()) {
            // Control button slots
            if (slot == CREATE_NEW_SLOT) {
                handleCreateNew();
            } else if (slot == CLOSE_SLOT) {
                player.closeInventory();
            }
            return;
        }

        Network clickedNetwork = playerNetworks.get(slot);

        switch (clickType) {
            case RIGHT:
                // Open configuration GUI
                handleConfigure(clickedNetwork);
                break;
            case LEFT:
            default:
                // Switch to network
                handleSwitchNetwork(clickedNetwork);
                break;
        }
    }

    private void handleCreateNew() {
        int maxNetworks = plugin.getConfigManager().getMaxNetworksPerPlayer();
        if (maxNetworks > 0 && playerNetworks.size() >= maxNetworks) {
            player.sendMessage(lang.getMessage("network.max_networks_reached")
                .replace("%s", String.valueOf(maxNetworks)));
            return;
        }

        player.closeInventory();
        player.sendMessage(lang.getMessage("network.create.prompt"));
        plugin.getSearchManager().startCreatingNetwork(player);
    }

    private void handleConfigure(Network network) {
        NetworkConfigGUI configGUI = new NetworkConfigGUI(player, network, plugin);
        configGUI.open();
    }

    private void handleSwitchNetwork(Network network) {
        if (plugin.getNetworkManager().setActiveNetwork(player, network.getName())) {
            player.sendMessage(lang.getMessage("network.switched").replace("%s", network.getName()));
            updateInventory();
        } else {
            player.sendMessage(lang.getMessage("network.switch_failed"));
        }
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }
}
