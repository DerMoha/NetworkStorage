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

    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_SEARCH = 46;
    private static final int SLOT_SORT = 47;
    private static final int SLOT_INFO = 48;
    private static final int SLOT_STATS = 49;
    private static final int SLOT_REFRESH = 52;
    private static final int SLOT_NEXT_PAGE = 53;

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
        long totalNetworkItems = networkItems.values().stream().mapToLong(Integer::longValue).sum();
        int uniqueTypes = networkItems.size();
        double capacity = network.getCapacityPercent();
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
            ItemStack displayItem = createDisplayItem(entry.getKey(), entry.getValue(), totalNetworkItems);
            inventory.setItem(slot, displayItem);
        }

        addControlButtons(currentPage, totalPages, totalNetworkItems, uniqueTypes, capacity);
    }

    private void addControlButtons(int page, int totalPages, long totalItems, int uniqueTypes, double capacity) {
        if (page > 0) {
            ItemStack prevButton = createGuiControlItem(
                    Material.ARROW,
                    lang.getMessage("terminal.prev_page"),
                    Collections.singletonList(String.format(lang.getMessage("terminal.page"), page + 1, Math.max(1, totalPages))),
                    "custom-model-data.gui.terminal.prev-page"
            );
            inventory.setItem(SLOT_PREV_PAGE, prevButton);
        }

        String searchTitle;
        List<String> searchLore;
        if (searchFilter.isEmpty()) {
            searchTitle = lang.getMessage("terminal.search.title");
            searchLore = Arrays.asList(
                    lang.getMessage("terminal.search.lore1"),
                    lang.getMessage("terminal.search.lore2")
            );
        } else {
            searchTitle = String.format(lang.getMessage("terminal.search.active"), searchFilter);
            searchLore = Arrays.asList(
                    lang.getMessage("terminal.search.filtered"),
                    lang.getMessage("terminal.search.change"),
                    lang.getMessage("terminal.search.clear")
            );
        }
        ItemStack searchButton = createGuiControlItem(Material.SPYGLASS, searchTitle, searchLore, "custom-model-data.gui.terminal.search");
        inventory.setItem(SLOT_SEARCH, searchButton);

        ItemStack sortButton = createGuiControlItem(
                Material.COMPARATOR,
                String.format(lang.getMessage("terminal.sort.title"), getSortDisplayName()),
                Arrays.asList(
                lang.getMessage("terminal.sort.lore1"),
                String.format(lang.getMessage("terminal.sort.lore2"), getSortDisplayName())
                ),
                "custom-model-data.gui.terminal.sort"
        );
        inventory.setItem(SLOT_SORT, sortButton);

        ItemStack infoButton = createGuiControlItem(
                Material.BOOK,
                lang.getMessage("terminal.info.title"),
                Arrays.asList(
                String.format(lang.getMessage("terminal.info.items"), uniqueTypes),
                String.format(lang.getMessage("total_items"), formatNumber(totalItems)),
                String.format(lang.getMessage("terminal.info.chests"), network.getChestLocations().size()),
                String.format(lang.getMessage("terminal.info.terminals"), network.getTerminalLocations().size()),
                String.format(lang.getMessage("terminal.info.capacity"), String.format("%.1f%%", capacity)),
                "",
                lang.getMessage("terminal.info.lore1"),
                lang.getMessage("terminal.info.lore2"),
                lang.getMessage("terminal.info.lore3")
                ),
                "custom-model-data.gui.terminal.info"
        );
        inventory.setItem(SLOT_INFO, infoButton);

        ItemStack statsButton = createGuiControlItem(
                Material.EMERALD,
                lang.getMessage("terminal.stats.title"),
                Collections.singletonList(lang.getMessage("terminal.stats.lore")),
                "custom-model-data.gui.terminal.stats"
        );
        inventory.setItem(SLOT_STATS, statsButton);

        ItemStack refreshButton = createGuiControlItem(
                Material.CLOCK,
                lang.getMessage("terminal.refresh.title"),
                Collections.singletonList(lang.getMessage("terminal.refresh.lore")),
                "custom-model-data.gui.terminal.refresh"
        );
        inventory.setItem(SLOT_REFRESH, refreshButton);

        if (page < totalPages - 1) {
            ItemStack nextButton = createGuiControlItem(
                    Material.ARROW,
                    lang.getMessage("terminal.next_page"),
                    Collections.singletonList(String.format(lang.getMessage("terminal.page"), page + 2, totalPages)),
                    "custom-model-data.gui.terminal.next-page"
            );
            inventory.setItem(SLOT_NEXT_PAGE, nextButton);
        }
    }

    private ItemStack createGuiControlItem(Material material, String displayName, List<String> lore, String customModelDataPath) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(lore);
            ItemUtils.applyCustomModelData(meta, plugin.getConfigManager().getOptionalCustomModelData(customModelDataPath));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createDisplayItem(ItemStack original, int totalCount, long totalNetworkItems) {
        ItemStack display = original.clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        }

        if (!original.hasItemMeta() || !original.getItemMeta().hasDisplayName()) {
            meta.setDisplayName(null);
        }

        List<String> lore = new ArrayList<>();
        lore.add(String.format(lang.getMessage("terminal.item.lore.total"), formatNumber(totalCount)));
        if (totalNetworkItems > 0) {
            double percentage = (double) totalCount / totalNetworkItems * 100.0;
            lore.add(String.format(lang.getMessage("terminal.item.lore.capacity_percentage"), percentage));
        }
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

    public static String getItemDisplayName(ItemStack item) {
        return ItemUtils.getItemDisplayName(item);
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
        if (!ensureAccess()) {
            return;
        }

        if (slot == SLOT_PREV_PAGE && currentPage > 0) {
            currentPage--;
            updateInventory();
            return;
        }

        if (slot == SLOT_NEXT_PAGE) {
            int totalPages = (int) Math.ceil((double) sortedItems.size() / ITEMS_PER_PAGE);
            if (currentPage < totalPages - 1) {
                currentPage++;
                updateInventory();
            }
            return;
        }

        if (slot == SLOT_SEARCH) {
            if (isRightClick && !searchFilter.isEmpty()) {
                searchFilter = "";
                currentPage = 0;
                updateInventory();
                player.sendMessage(lang.getMessage("terminal.search.cleared"));
            } else {
                plugin.getSearchManager().startSearch(player, this);
                plugin.getChestInteractListener().setTransitioningToSearch(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(lang.getMessage("terminal.search.prompt"));
                player.sendMessage(lang.getMessage("terminal.search.cancel_hint"));
            }
            return;
        }

        if (slot == SLOT_SORT) {
            cycleSortType();
            currentPage = 0;
            updateInventory();
            return;
        }

        if (slot == SLOT_STATS) {
            plugin.getChestInteractListener().setTransitioningToStats(player.getUniqueId());
            StatsGUI statsGUI = new StatsGUI(player, network, plugin, this);
            statsGUI.open();
            return;
        }

        if (slot == SLOT_REFRESH) {
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
        int requestedAmount = Math.min(availableAmount, amountToTake);

        if (requestedAmount <= 0) {
            player.sendMessage(lang.getMessage("terminal.no_items"));
            return;
        }

        ItemStack requestedItem = itemType.clone();
        requestedItem.setAmount(requestedAmount);
        ItemStack removedItem = network.removeFromNetwork(requestedItem, requestedAmount);

        if (removedItem == null || removedItem.getAmount() <= 0) {
            player.sendMessage(lang.getMessage("terminal.no_items"));
            plugin.getServer().getScheduler().runTask(plugin, this::updateInventory);
            return;
        }

        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(removedItem.clone());
        int returnedToNetworkAmount = 0;
        boolean droppedItems = false;

        for (ItemStack overflowItem : overflow.values()) {
            int overflowAmount = overflowItem.getAmount();
            ItemStack remaining = network.addToNetwork(overflowItem.clone());

            if (remaining == null || remaining.getAmount() == 0) {
                returnedToNetworkAmount += overflowAmount;
                continue;
            }

            returnedToNetworkAmount += overflowAmount - remaining.getAmount();
            if (remaining.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                droppedItems = true;
            }
        }

        int withdrawnAmount = removedItem.getAmount() - returnedToNetworkAmount;
        if (withdrawnAmount <= 0) {
            player.sendMessage(lang.getMessage("terminal.inventory_full_returned"));
            plugin.getServer().getScheduler().runTask(plugin, this::updateInventory);
            return;
        }

        network.recordItemsWithdrawn(player, withdrawnAmount);
        player.sendMessage(String.format(lang.getMessage("terminal.took_items"), withdrawnAmount, getItemDisplayName(itemType)));
        if (!overflow.isEmpty() && !droppedItems) {
            player.sendMessage(lang.getMessage("terminal.inventory_full_returned"));
        }
        if (droppedItems) {
            player.sendMessage(lang.getMessage("terminal.items_dropped"));
        }

        plugin.getServer().getScheduler().runTask(plugin, this::updateInventory);
    }

    private void cycleSortType() {
        sortType = SortType.values()[(sortType.ordinal() + 1) % SortType.values().length];
        player.sendMessage(String.format(lang.getMessage("terminal.sort_changed"), getSortDisplayName()));
    }

    public void setSearchFilter(String filter) {
        if (!ensureAccess()) {
            return;
        }

        this.searchFilter = filter;
        this.currentPage = 0;
        updateInventory();
    }

    public boolean open() {
        if (!ensureAccess()) {
            return false;
        }

        player.openInventory(inventory);
        return true;
    }

    private boolean ensureAccess() {
        if (network.canAccess(player)) {
            return true;
        }

        player.closeInventory();
        player.sendMessage(lang.getMessage("trust.no_permission_access"));
        return false;
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
