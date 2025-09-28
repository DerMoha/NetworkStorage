package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class StatsGUI implements InventoryHolder {

    private final Inventory inventory;
    private final Player player;
    private final LanguageManager lang;
    private final TerminalGUI previousGUI;

    public StatsGUI(Player player, Network network, NetworkStoragePlugin plugin, TerminalGUI previousGUI) {
        this.player = player;
        this.lang = plugin.getLanguageManager();
        this.previousGUI = previousGUI;
        this.inventory = Bukkit.createInventory(this, 54, lang.getMessage("stats.title"));

        updateInventory(network);
    }

    private void updateInventory(Network network) {
        inventory.clear();

        List<PlayerStat> stats = new ArrayList<>(network.getPlayerStats().values());

        // Sort by most deposited
        stats.sort(Comparator.comparingLong(PlayerStat::getItemsDeposited).reversed());

        // Display top players (up to 45)
        for (int i = 0; i < Math.min(stats.size(), 45); i++) {
            PlayerStat stat = stats.get(i);
            inventory.setItem(i, createStatItem(stat, i + 1));
        }

        // Add back button
        ItemStack backButton = new ItemStack(Material.BARRIER);
        ItemMeta meta = backButton.getItemMeta();
        meta.setDisplayName(lang.getMessage("stats.back"));
        backButton.setItemMeta(meta);
        inventory.setItem(49, backButton);
    }

    private ItemStack createStatItem(PlayerStat stat, int rank) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(stat.getPlayerUUID()));
            meta.setDisplayName(String.format(lang.getMessage("stats.player.name"), rank, stat.getPlayerName()));

            List<String> lore = new ArrayList<>();
            lore.add(String.format(lang.getMessage("stats.player.deposited"), stat.getItemsDeposited()));
            lore.add(String.format(lang.getMessage("stats.player.withdrawn"), stat.getItemsWithdrawn()));
            long balance = stat.getItemsDeposited() - stat.getItemsWithdrawn();
            lore.add(String.format(lang.getMessage("stats.player.balance"), balance));

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void handleClick(int slot) {
        if (slot == 49) { // Back button
            previousGUI.open();
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
