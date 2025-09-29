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

import java.util.*;
import java.util.stream.Collectors;

public class TerminalGUI implements InventoryHolder {

    private final Player player;
    private final Network network;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    private Inventory inventory;
    private int currentPage = 0;
    private SortType sortType = SortType.ALPHABETICAL;
    private List<Map.Entry<ItemStack, Integer>> sortedItems;
    private String searchFilter = "";

    private static final int ITEMS_PER_PAGE = 45;
    private static final int GUI_SIZE = 54;

    public enum SortType {
        ALPHABETICAL,
        COUNT_DESC,
        COUNT_ASC
    }

    public TerminalGUI(Player player, Network network, NetworkStoragePlugin plugin) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.inventory = Bukkit.createInventory(this, GUI_SIZE, lang.getMessage("terminal.title"));
        updateInventory();
    }

    public void updateInventory() {
        inventory.clear();

        Map<ItemStack, Integer> networkItems = network.getNetworkItems();
        sortedItems = new ArrayList<>(networkItems.entrySet());

        if (!searchFilter.isEmpty()) {
            sortedItems = sortedItems.stream()
                    .filter(entry -> {
                        ItemStack item = entry.getKey();
                        String lowerCaseFilter = searchFilter.toLowerCase();

                        // Check custom display name
                        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                            if (item.getItemMeta().getDisplayName().toLowerCase().contains(lowerCaseFilter)) {
                                return true;
                            }
                        }

                        // Check internal material name (e.g., "diamond_sword")
                        if (item.getType().getKey().getKey().toLowerCase().contains(lowerCaseFilter)) {
                            return true;
                        }

                        // Fallback check for formatted English name
                        return getItemDisplayName(item).toLowerCase().contains(lowerCaseFilter);
                    })
                    .collect(Collectors.toList());
        }

        switch (sortType) {
            case ALPHABETICAL:
                sortedItems.sort(Comparator.comparing(a -> getItemDisplayName(a.getKey()), String.CASE_INSENSITIVE_ORDER));
                break;
            case COUNT_DESC:
                sortedItems.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
                break;
            case COUNT_ASC:
                sortedItems.sort(Map.Entry.comparingByValue());
                break;
        }

        int totalPages = (int) Math.ceil((double) sortedItems.size() / ITEMS_PER_PAGE);
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, sortedItems.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            Map.Entry<ItemStack, Integer> entry = sortedItems.get(i);
            ItemStack displayItem = createDisplayItem(entry.getKey(), entry.getValue());
            inventory.setItem(slot, displayItem);
        }

        addControlButtons(currentPage, totalPages);
    }

    private void addControlButtons(int page, int totalPages) {
        if (page > 0) {
            ItemStack prevButton = new ItemStack(Material.ARROW);
            ItemMeta meta = prevButton.getItemMeta();
            meta.setDisplayName(lang.getMessage("terminal.prev_page"));
            meta.setLore(Collections.singletonList(String.format(lang.getMessage("terminal.page"), page + 1, Math.max(1, totalPages))));
            prevButton.setItemMeta(meta);
            inventory.setItem(45, prevButton);
        }

        ItemStack searchButton = new ItemStack(Material.SPYGLASS);
        ItemMeta searchMeta = searchButton.getItemMeta();
        if (searchFilter.isEmpty()) {
            searchMeta.setDisplayName(lang.getMessage("terminal.search.title"));
            searchMeta.setLore(Arrays.asList(
                    lang.getMessage("terminal.search.lore1"),
                    lang.getMessage("terminal.search.lore2")
            ));
        } else {
            searchMeta.setDisplayName(String.format(lang.getMessage("terminal.search.active"), searchFilter));
            searchMeta.setLore(Arrays.asList(
                    lang.getMessage("terminal.search.filtered"),
                    lang.getMessage("terminal.search.change"),
                    lang.getMessage("terminal.search.clear")
            ));
        }
        searchButton.setItemMeta(searchMeta);
        inventory.setItem(46, searchButton);

        ItemStack sortButton = new ItemStack(Material.COMPARATOR);
        ItemMeta sortMeta = sortButton.getItemMeta();
        sortMeta.setDisplayName(String.format(lang.getMessage("terminal.sort.title"), getSortDisplayName()));
        sortMeta.setLore(Arrays.asList(
                lang.getMessage("terminal.sort.lore1"),
                String.format(lang.getMessage("terminal.sort.lore2"), getSortDisplayName())
        ));
        sortButton.setItemMeta(sortMeta);
        inventory.setItem(47, sortButton);

        long totalItems = network.getNetworkItems().values().stream().mapToLong(Integer::longValue).sum();
        int uniqueTypes = network.getNetworkItems().size();
        double capacity = network.getCapacityPercent();

        ItemStack infoButton = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoButton.getItemMeta();
        infoMeta.setDisplayName(lang.getMessage("terminal.info.title"));
        infoMeta.setLore(Arrays.asList(
                String.format(lang.getMessage("terminal.info.items"), uniqueTypes),
                String.format(lang.getMessage("total_items"), formatNumber(totalItems)),
                String.format(lang.getMessage("terminal.info.chests"), network.getChestLocations().size()),
                String.format(lang.getMessage("terminal.info.terminals"), network.getTerminalLocations().size()),
                String.format(lang.getMessage("terminal.info.capacity"), String.format("%.1f%%", capacity)),
                "",
                lang.getMessage("terminal.info.lore1"),
                lang.getMessage("terminal.info.lore2"),
                lang.getMessage("terminal.info.lore3")
        ));
        infoButton.setItemMeta(infoMeta);
        inventory.setItem(48, infoButton);

        ItemStack statsButton = new ItemStack(Material.EMERALD);
        ItemMeta statsMeta = statsButton.getItemMeta();
        statsMeta.setDisplayName(lang.getMessage("terminal.stats.title"));
        statsMeta.setLore(Collections.singletonList(lang.getMessage("terminal.stats.lore")));
        statsButton.setItemMeta(statsMeta);
        inventory.setItem(49, statsButton);

        ItemStack refreshButton = new ItemStack(Material.CLOCK);
        ItemMeta refreshMeta = refreshButton.getItemMeta();
        refreshMeta.setDisplayName(lang.getMessage("terminal.refresh.title"));
        refreshMeta.setLore(Collections.singletonList(lang.getMessage("terminal.refresh.lore")));
        refreshButton.setItemMeta(refreshMeta);
        inventory.setItem(52, refreshButton);

        if (page < totalPages - 1) {
            ItemStack nextButton = new ItemStack(Material.ARROW);
            ItemMeta meta = nextButton.getItemMeta();
            meta.setDisplayName(lang.getMessage("terminal.next_page"));
            meta.setLore(Collections.singletonList(String.format(lang.getMessage("terminal.page"), page + 2, totalPages)));
            nextButton.setItemMeta(meta);
            inventory.setItem(53, nextButton);
        }
    }

    private ItemStack createDisplayItem(ItemStack original, int totalCount) {
        ItemStack display = original.clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        }

        // DO NOT set a display name if the item doesn't have a custom one.
        // The client will handle localization. The clone operation preserves any existing custom name.
        if (!original.hasItemMeta() || !original.getItemMeta().hasDisplayName()) {
            meta.setDisplayName(null);
        }

        List<String> lore = new ArrayList<>();
        lore.add(String.format(lang.getMessage("terminal.item.lore.total"), formatNumber(totalCount)));
        lore.add(String.format(lang.getMessage("terminal.item.lore.stacks"), totalCount / original.getMaxStackSize()));
        if (totalCount % original.getMaxStackSize() > 0) {
            lore.add(String.format(lang.getMessage("terminal.item.lore.partial"), totalCount % original.getMaxStackSize()));
        }
        lore.add("");
        lore.add(String.format(lang.getMessage("terminal.item.lore.take_stack"), original.getMaxStackSize()));
        lore.add(lang.getMessage("terminal.item.lore.take_half"));
        lore.add(lang.getMessage("terminal.item.lore.take_one"));
        if (original.hasItemMeta() && original.getItemMeta().hasLore()) {
            lore.add("");
            lore.add(lang.getMessage("terminal.item.lore.properties"));
            if (original.getItemMeta().getLore() != null) {
                lore.addAll(original.getItemMeta().getLore());
            }
        }
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    public String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        // Fallback to a formatted material name for sorting and searching
        String materialName = item.getType().toString().replace('_', ' ').toLowerCase();
        String[] words = materialName.split(" ");
        StringBuilder displayName = new StringBuilder();
        for (String word : words) {
            if (displayName.length() > 0) {
                displayName.append(" ");
            }
            displayName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return displayName.toString();
    }

    private String getSortDisplayName() {
        switch (sortType) {
            case ALPHABETICAL:
                return lang.getMessage("terminal.sort.alpha");
            case COUNT_DESC:
                return lang.getMessage("terminal.sort.desc");
            case COUNT_ASC:
                return lang.getMessage("terminal.sort.asc");
            default:
                return lang.getMessage("terminal.sort.unknown");
        }
    }

    public String formatNumber(long number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    public void handleClick(int slot, boolean isRightClick, boolean isShiftClick, boolean isLeftClick) {
        if (slot == 45 && currentPage > 0) {
            currentPage--;
            updateInventory();
            return;
        }

        if (slot == 53) {
            int totalPages = (int) Math.ceil((double) sortedItems.size() / ITEMS_PER_PAGE);
            if (currentPage < totalPages - 1) {
                currentPage++;
                updateInventory();
            }
            return;
        }

        if (slot == 46) {
            if (isRightClick && !searchFilter.isEmpty()) {
                searchFilter = "";
                currentPage = 0;
                updateInventory();
                player.sendMessage(lang.getMessage("terminal.search.cleared"));
            } else {
                plugin.getSearchManager().startSearch(player, this);
                player.closeInventory();
                player.sendMessage(lang.getMessage("terminal.search.prompt"));
                player.sendMessage(lang.getMessage("terminal.search.cancel_hint"));
            }
            return;
        }

        if (slot == 47) {
            cycleSortType();
            currentPage = 0;
            updateInventory();
            return;
        }

        if (slot == 49) { // Stats button
            plugin.getChestInteractListener().setTransitioningToStats(player.getUniqueId());
            StatsGUI statsGUI = new StatsGUI(player, network, plugin, this);
            statsGUI.open();
            return;
        }

        if (slot == 52) {
            updateInventory();
            player.sendMessage(lang.getMessage("terminal.refreshed"));
            return;
        }

        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            int itemIndex = (currentPage * ITEMS_PER_PAGE) + slot;
            if (itemIndex < sortedItems.size()) {
                Map.Entry<ItemStack, Integer> entry = sortedItems.get(itemIndex);
                ItemStack originalItem = entry.getKey();
                int availableAmount = entry.getValue();
                int amountToTake = 0;

                if (isLeftClick && !isShiftClick) { // Left-click: take 1
                    amountToTake = 1;
                } else if (isRightClick && !isShiftClick) { // Right-click: take stack
                    amountToTake = originalItem.getMaxStackSize();
                } else if (isRightClick && isShiftClick) { // Shift-right-click: take half stack
                    amountToTake = Math.max(1, originalItem.getMaxStackSize() / 2);
                }

                if (amountToTake > 0) {
                    handleItemExtraction(originalItem, availableAmount, amountToTake);
                }
            }
        }
    }

    private void handleItemExtraction(ItemStack itemType, int availableAmount, int amountToTake) {
        int finalAmount = Math.min(availableAmount, amountToTake);

        if (finalAmount <= 0) {
            player.sendMessage(lang.getMessage("terminal.no_items"));
            return;
        }

        ItemStack removedItem = network.removeFromNetwork(itemType, finalAmount);

        if (removedItem != null && removedItem.getAmount() > 0) {
            network.recordItemsWithdrawn(player, removedItem.getAmount());
            plugin.getNetworkManager().saveNetworks();

            HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(removedItem);

            if (!remaining.isEmpty()) {
                for (ItemStack leftover : remaining.values()) {
                    ItemStack stillRemaining = network.addToNetwork(leftover);
                    if (stillRemaining != null && stillRemaining.getAmount() > 0) {
                        player.getWorld().dropItemNaturally(player.getLocation(), stillRemaining);
                        player.sendMessage(lang.getMessage("terminal.items_dropped"));
                    }
                }
            }
            player.sendMessage(String.format(lang.getMessage("terminal.took_items"), removedItem.getAmount(), getItemDisplayName(itemType)));

            plugin.getServer().getScheduler().runTask(plugin, this::updateInventory);
        } else {
            player.sendMessage(lang.getMessage("terminal.could_not_remove"));
        }
    }

    private void cycleSortType() {
        sortType = SortType.values()[(sortType.ordinal() + 1) % SortType.values().length];
        player.sendMessage(String.format(lang.getMessage("terminal.sort_changed"), getSortDisplayName()));
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

    public Network getNetwork() {
        return network;
    }
}
