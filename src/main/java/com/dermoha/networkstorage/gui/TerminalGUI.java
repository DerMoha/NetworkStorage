package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class TerminalGUI implements InventoryHolder {

    private final Player player;
    private final StorageNetwork network;
    private final NetworkStoragePlugin plugin;

    private Inventory inventory;
    private int currentPage = 0;
    private SortType sortType = SortType.ALPHABETICAL;
    private List<Map.Entry<ItemStack, Integer>> sortedItems;
    private String searchFilter = "";

    private static final int ITEMS_PER_PAGE = 45; // 5 rows for items
    private static final int GUI_SIZE = 54; // 6 rows total

    public enum SortType {
        ALPHABETICAL,
        COUNT_DESC,
        COUNT_ASC
    }

    public TerminalGUI(Player player, StorageNetwork network, NetworkStoragePlugin plugin) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, GUI_SIZE,
                ChatColor.DARK_BLUE + "Storage Network Terminal");

        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        // Get and filter network items
        Map<ItemStack, Integer> networkItems = network.getNetworkItems();
        sortedItems = new ArrayList<>(networkItems.entrySet());

        // Apply search filter
        if (!searchFilter.isEmpty()) {
            sortedItems = sortedItems.stream()
                    .filter(entry -> getItemDisplayName(entry.getKey())
                            .toLowerCase().contains(searchFilter.toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }

        // Sort items
        switch (sortType) {
            case ALPHABETICAL:
                sortedItems.sort((a, b) -> {
                    String nameA = getItemDisplayName(a.getKey());
                    String nameB = getItemDisplayName(b.getKey());
                    return nameA.compareToIgnoreCase(nameB);
                });
                break;
            case COUNT_DESC:
                sortedItems.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                break;
            case COUNT_ASC:
                sortedItems.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
                break;
        }

        // Calculate pagination
        int totalPages = (int) Math.ceil((double) sortedItems.size() / ITEMS_PER_PAGE);
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, sortedItems.size());

        // Add items to inventory (slots 0-44)
        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<ItemStack, Integer> entry = sortedItems.get(i);
            ItemStack displayItem = createDisplayItem(entry.getKey(), entry.getValue());
            inventory.setItem(slot, displayItem);
            slot++;
        }

        // Add control buttons in bottom row
        addControlButtons(currentPage, totalPages);
    }

    private void addControlButtons(int page, int totalPages) {
        // Previous page button (slot 45)
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Material.ARROW);
            ItemMeta meta = prevButton.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Previous Page");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Page " + page + "/" + Math.max(1, totalPages)));
            prevButton.setItemMeta(meta);
            inventory.setItem(45, prevButton);
        }

        // Search button (slot 46)
        ItemStack searchButton = new ItemStack(Material.SPYGLASS);
        ItemMeta searchMeta = searchButton.getItemMeta();
        if (searchFilter.isEmpty()) {
            searchMeta.setDisplayName(ChatColor.AQUA + "Search Items");
            searchMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Click to search for items",
                    ChatColor.GRAY + "Type item name in chat"
            ));
        } else {
            searchMeta.setDisplayName(ChatColor.AQUA + "Search: " + ChatColor.WHITE + searchFilter);
            searchMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Showing filtered results",
                    ChatColor.GRAY + "Click to change search",
                    ChatColor.YELLOW + "Right-click to clear"
            ));
        }
        searchButton.setItemMeta(searchMeta);
        inventory.setItem(46, searchButton);

        // Sort button (slot 47)
        ItemStack sortButton = new ItemStack(Material.COMPARATOR);
        ItemMeta sortMeta = sortButton.getItemMeta();
        sortMeta.setDisplayName(ChatColor.YELLOW + "Sort: " + getSortDisplayName());
        sortMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Click to change sorting",
                ChatColor.GRAY + "Current: " + getSortDisplayName()
        ));
        sortButton.setItemMeta(sortMeta);
        inventory.setItem(47, sortButton);

        // Network info button (slot 48)
        // Calculate storage info
        int totalSlots = 0;
        int usedSlots = 0;
        for (Location chestLoc : network.getChestLocations()) {
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                int chestSize = chest.getInventory().getSize();
                totalSlots += chestSize;

                for (ItemStack item : chest.getInventory().getContents()) {
                    if (item != null && item.getType() != Material.AIR) {
                        usedSlots++;
                    }
                }
            }
        }

        ItemStack infoButton = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoButton.getItemMeta();
        infoMeta.setDisplayName(ChatColor.AQUA + "Network Info");
        infoMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Total Items: " + sortedItems.size() + " types",
                ChatColor.GRAY + "Connected Chests: " + network.getChestLocations().size(),
                ChatColor.GRAY + "Terminals: " + network.getTerminalLocations().size(),
                ChatColor.GRAY + "Storage Used: " + usedSlots + "/" + totalSlots + " slots",
                ChatColor.GRAY + "Storage Free: " + ChatColor.GREEN + (totalSlots - usedSlots) + " slots",
                ChatColor.GRAY + "Capacity: " + String.format("%.1f%%", (usedSlots * 100.0 / Math.max(1, totalSlots))),
                "",
                ChatColor.YELLOW + "Left-click items to take 1",
                ChatColor.YELLOW + "Right-click items to take a stack",
                ChatColor.GOLD + "Shift+Click items in YOUR inventory to deposit!"
        ));
        infoButton.setItemMeta(infoMeta);
        inventory.setItem(48, infoButton);

        // Refresh button (slot 52)
        ItemStack refreshButton = new ItemStack(Material.CLOCK);
        ItemMeta refreshMeta = refreshButton.getItemMeta();
        refreshMeta.setDisplayName(ChatColor.GREEN + "Refresh");
        refreshMeta.setLore(Arrays.asList(ChatColor.GRAY + "Update network contents"));
        refreshButton.setItemMeta(refreshMeta);
        inventory.setItem(52, refreshButton);

        // Next page button (slot 53)
        if (page < totalPages - 1) {
            ItemStack nextButton = new ItemStack(Material.ARROW);
            ItemMeta meta = nextButton.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "Next Page");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Page " + (page + 2) + "/" + totalPages));
            nextButton.setItemMeta(meta);
            inventory.setItem(53, nextButton);
        }
    }

    private ItemStack createDisplayItem(ItemStack original, int totalCount) {
        ItemStack display = original.clone();
        display.setAmount(1); // Always show as 1 in GUI

        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        }

        String itemName = getItemDisplayName(original);
        meta.setDisplayName(ChatColor.WHITE + itemName);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Total in network: " + ChatColor.YELLOW + formatNumber(totalCount));
        lore.add(ChatColor.GRAY + "Stacks: " + ChatColor.YELLOW + (totalCount / original.getMaxStackSize()));
        if (totalCount % original.getMaxStackSize() > 0) {
            lore.add(ChatColor.GRAY + "Partial stack: " + ChatColor.YELLOW + (totalCount % original.getMaxStackSize()));
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "Right-click to take a stack (" + original.getMaxStackSize() + ")");
        lore.add(ChatColor.GREEN + "Shift + Right-click to take half stack");
        lore.add(ChatColor.AQUA + "Left-click to take 1 item");

        // Add original lore if it exists
        if (original.hasItemMeta() && original.getItemMeta().hasLore()) {
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Item Properties:");
            lore.addAll(original.getItemMeta().getLore());
        }

        meta.setLore(lore);
        display.setItemMeta(meta);

        return display;
    }

    public String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }

        // Convert material name to readable format
        String materialName = item.getType().toString().toLowerCase();
        String[] words = materialName.split("_");
        StringBuilder displayName = new StringBuilder();

        for (String word : words) {
            if (displayName.length() > 0) {
                displayName.append(" ");
            }
            displayName.append(word.substring(0, 1).toUpperCase())
                    .append(word.substring(1));
        }

        return displayName.toString();
    }

    private String getSortDisplayName() {
        switch (sortType) {
            case ALPHABETICAL:
                return "Alphabetical";
            case COUNT_DESC:
                return "Count (High to Low)";
            case COUNT_ASC:
                return "Count (Low to High)";
            default:
                return "Unknown";
        }
    }

    private String formatNumber(int number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    /**
     * Gibt die aktuelle Kapazität des Netzwerks in Prozent zurück
     */
    public double getCurrentCapacityPercent() {
        int totalSlots = 0;
        int usedSlots = 0;
        for (Location chestLoc : network.getChestLocations()) {
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                int chestSize = chest.getInventory().getSize();
                totalSlots += chestSize;
                for (ItemStack item : chest.getInventory().getContents()) {
                    if (item != null && item.getType() != Material.AIR) {
                        usedSlots++;
                    }
                }
            }
        }
        return totalSlots > 0 ? (usedSlots * 100.0 / totalSlots) : 0.0;
    }

    public void handleClick(int slot, boolean isRightClick, boolean isShiftClick, boolean isLeftClick) {
        // Handle control buttons
        if (slot == 45 && currentPage > 0) {
            // Previous page
            currentPage--;
            updateInventory();
            return;
        }

        if (slot == 53) {
            // Next page
            int totalPages = (int) Math.ceil((double) sortedItems.size() / ITEMS_PER_PAGE);
            if (currentPage < totalPages - 1) {
                currentPage++;
                updateInventory();
            }
            return;
        }

        if (slot == 46) {
            // Search button
            if (isRightClick && !searchFilter.isEmpty()) {
                // Clear search
                searchFilter = "";
                currentPage = 0;
                updateInventory();
                player.sendMessage(ChatColor.YELLOW + "Search cleared!");
            } else {
                // Start search
                player.closeInventory();
                player.sendMessage(ChatColor.AQUA + "Type the name of the item you want to search for:");
                player.sendMessage(ChatColor.GRAY + "Type 'cancel' to cancel the search.");
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    startSearchMode();
                }, 1L);
            }
            return;
        }

        if (slot == 47) {
            // Sort button
            cycleSortType();
            currentPage = 0;
            updateInventory();
            return;
        }

        if (slot == 52) {
            // Refresh button
            updateInventory();
            player.sendMessage(ChatColor.GREEN + "Network refreshed!");
            return;
        }

        // Handle item clicks (slots 0-44)
        if (slot < ITEMS_PER_PAGE) {
            int itemIndex = (currentPage * ITEMS_PER_PAGE) + slot;
            if (itemIndex < sortedItems.size()) {
                Map.Entry<ItemStack, Integer> entry = sortedItems.get(itemIndex);

                if (isLeftClick) {
                    handleItemExtraction(entry.getKey(), entry.getValue(), false, 1);
                } else if (isRightClick) {
                    handleItemExtraction(entry.getKey(), entry.getValue(), isShiftClick, 0);
                }
            }
        }
    }

    private void startSearchMode() {
        // This will be handled by a separate chat listener
        plugin.getSearchManager().startSearch(player, this);
    }

    private void handleItemExtraction(ItemStack itemType, int availableAmount, boolean isHalfStack, int specificAmount) {
        int amountToTake;

        if (specificAmount > 0) {
            // Taking specific amount (like 1 item)
            amountToTake = specificAmount;
        } else {
            // Taking stack or half stack
            int maxStackSize = itemType.getMaxStackSize();
            amountToTake = isHalfStack ? maxStackSize / 2 : maxStackSize;
        }

        if (availableAmount < amountToTake) {
            amountToTake = availableAmount;
        }

        if (amountToTake <= 0) {
            player.sendMessage(ChatColor.RED + "No more of this item available!");
            return;
        }

        // Try to remove from network
        ItemStack removedItem = network.removeFromNetwork(itemType, amountToTake);

        if (removedItem != null && removedItem.getAmount() > 0) {
            // Try to give to player
            HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(removedItem);

            if (!remaining.isEmpty()) {
                // Player inventory full, drop items or put back in network
                for (ItemStack leftover : remaining.values()) {
                    // Try to put back in network
                    ItemStack stillRemaining = network.addToNetwork(leftover);
                    if (stillRemaining != null) {
                        // Drop on ground as last resort
                        player.getWorld().dropItemNaturally(player.getLocation(), stillRemaining);
                        player.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory is full!");
                    }
                }
            }

            String actionDescription = specificAmount == 1 ? "1" :
                    (isHalfStack ? "half stack of" : "stack of");
            player.sendMessage(ChatColor.GREEN + "Took " + actionDescription + " " + removedItem.getAmount() + "x " +
                    getItemDisplayName(itemType));

            // Update GUI
            updateInventory();
        } else {
            player.sendMessage(ChatColor.RED + "Could not remove items from network!");
        }
    }

    private void cycleSortType() {
        switch (sortType) {
            case ALPHABETICAL:
                sortType = SortType.COUNT_DESC;
                break;
            case COUNT_DESC:
                sortType = SortType.COUNT_ASC;
                break;
            case COUNT_ASC:
                sortType = SortType.ALPHABETICAL;
                break;
        }

        player.sendMessage(ChatColor.YELLOW + "Sort changed to: " + getSortDisplayName());
    }

    public void setSearchFilter(String filter) {
        this.searchFilter = filter;
        this.currentPage = 0;
        updateInventory();
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

    public StorageNetwork getNetwork() {
        return network;
    }
}

