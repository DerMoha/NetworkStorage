package com.dermoha.networkstorage.gui;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.Consumer;

public class TerminalGUI implements PluginInventory {

    private final Player player;
    private final Network network;
    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    private Inventory inventory;
    private int currentPage = 0;
    private final SortType defaultSort;
    private SortType sortType;
    private TerminalItemSelection.Page selection;
    private String searchFilter = "";
    private BulkDepositPlan pendingDeposit;
    private boolean refreshPending;
    private boolean forceScanNextUpdate;
    private final Consumer<NetworkScanResult> scanCompletionCallback = result -> {
        forceScanNextUpdate = false;
        requestRefresh();
    };

    private static final int ITEMS_PER_PAGE = TerminalItemSelection.PAGE_SIZE;
    private static final int GUI_SIZE = 54;

    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_SEARCH = 46;
    private static final int SLOT_SORT = 47;
    private static final int SLOT_INFO = 48;
    private static final int SLOT_STATS = 49;
    private static final int SLOT_DEPOSIT_ALL = 50;
    private static final int SLOT_CONFIRM_DEPOSIT = 51;
    private static final int SLOT_REFRESH = 52;
    private static final int SLOT_NEXT_PAGE = 53;

    public TerminalGUI(Player player, Network network, NetworkStoragePlugin plugin, SortType initialSort) {
        this.player = player;
        this.network = network;
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.defaultSort = plugin.getConfigManager().getDefaultSort();
        this.sortType = initialSort;
        this.inventory = Bukkit.createInventory(this, GUI_SIZE, lang.getMessage("terminal.title"));
        updateInventory();
    }

    public void updateInventory() {
        refreshPending = false;
        inventory.clear();

        boolean forceScan = forceScanNextUpdate;
        forceScanNextUpdate = false;
        NetworkScanResult scan = plugin.getNetworkManager().getNetworkScan(
                network,
                forceScan,
                forceScan || network.getScanResult().status() != com.dermoha.networkstorage.storage.NetworkScanStatus.COMPLETE
                        ? scanCompletionCallback : null);
        Map<ItemStack, Integer> networkItems = scan.items();
        long totalNetworkItems = scan.totalItems();
        int uniqueTypes = scan.uniqueTypes();
        double capacity = scan.capacityPercent();
        boolean showNumericSummary = scan.hasAuthoritativeData() || network.hasLastCompleteScan();
        selection = TerminalItemSelection.select(networkItems, searchFilter, sortType, currentPage);
        currentPage = selection.index();
        int totalPages = selection.totalPages();
        for (int slot = 0; slot < selection.items().size(); slot++) {
            Map.Entry<ItemStack, Integer> entry = selection.items().get(slot);
            inventory.setItem(slot, createDisplayItem(entry.getKey(), entry.getValue(), totalNetworkItems));
        }

        addControlButtons(currentPage, totalPages, totalNetworkItems, uniqueTypes, capacity, scan, showNumericSummary);
    }

    private void addControlButtons(int page,
                                   int totalPages,
                                   long totalItems,
                                   int uniqueTypes,
                                   double capacity,
                                   NetworkScanResult scan,
                                   boolean showNumericSummary) {
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
                String.format(lang.getMessage("terminal.sort.lore2"), getSortDisplayName()),
                lang.getMessage("terminal.sort.lore3")
                ),
                "custom-model-data.gui.terminal.sort"
        );
        inventory.setItem(SLOT_SORT, sortButton);

        List<String> infoLore = new ArrayList<>();
        if (showNumericSummary) {
            infoLore.add(String.format(lang.getMessage("terminal.info.items"), uniqueTypes));
            infoLore.add(String.format(lang.getMessage("total_items"), formatNumber(totalItems)));
            infoLore.add(String.format(lang.getMessage("terminal.info.capacity"), String.format("%.1f%%", capacity)));
            if (!scan.hasAuthoritativeData()) {
                infoLore.add("§eScan status: §fstale (last complete scan)");
            }
        } else {
            infoLore.add("§6Items: scan pending/incomplete");
            infoLore.add("§6Capacity: scan pending/incomplete");
        }
        infoLore.add(String.format(lang.getMessage("terminal.info.chests"), network.getChestLocations().size()));
        infoLore.add(String.format(lang.getMessage("terminal.info.terminals"), network.getTerminalLocations().size()));
        if (scan.status() != com.dermoha.networkstorage.storage.NetworkScanStatus.COMPLETE) {
            infoLore.add("§eScan status: §f" + scan.status().name().toLowerCase(Locale.ROOT));
        }
        infoLore.add("");
        infoLore.add(lang.getMessage("terminal.info.lore1"));
        infoLore.add(lang.getMessage("terminal.info.lore2"));
        infoLore.add(lang.getMessage("terminal.info.lore3"));

        ItemStack infoButton = createGuiControlItem(
                Material.BOOK,
                lang.getMessage("terminal.info.title"),
                infoLore,
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

        if (pendingDeposit == null) {
            inventory.setItem(SLOT_DEPOSIT_ALL, createGuiControlItem(Material.HOPPER,
                    lang.getMessage("terminal.bulk.title"), Arrays.asList(
                            lang.getMessage("terminal.bulk.scope"), lang.getMessage("terminal.bulk.protected")),
                    "custom-model-data.gui.terminal.deposit-all"));
        } else {
            inventory.setItem(SLOT_DEPOSIT_ALL, createGuiControlItem(Material.BARRIER,
                    lang.getMessage("terminal.bulk.cancel"), List.of(lang.getMessage("terminal.bulk.protected")),
                    "custom-model-data.gui.terminal.deposit-all"));
            inventory.setItem(SLOT_CONFIRM_DEPOSIT, createGuiControlItem(Material.LIME_CONCRETE,
                    lang.getMessage("terminal.bulk.confirm"), Arrays.asList(
                            String.format(lang.getMessage("terminal.bulk.preview"), pendingDeposit.itemCount(), pendingDeposit.slots().size()),
                            lang.getMessage("terminal.bulk.scope"), lang.getMessage("terminal.bulk.protected")),
                    "custom-model-data.gui.terminal.deposit-all"));
        }

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
        return ItemUtils.formatNumber(number);
    }

    public void handleClick(int slot, boolean isRightClick, boolean isShiftClick, boolean isLeftClick) {
        if (!ensureAccess()) {
            return;
        }

        if (slot == SLOT_DEPOSIT_ALL && isLeftClick && !isShiftClick) {
            if (pendingDeposit != null) {
                cancelBulkDeposit();
                requestRefresh();
            } else prepareBulkDeposit();
            return;
        }
        if (slot == SLOT_CONFIRM_DEPOSIT && isLeftClick && !isShiftClick) {
            confirmBulkDeposit();
            return;
        }
        boolean hadPendingDeposit = pendingDeposit != null;
        cancelBulkDeposit();
        if (hadPendingDeposit) requestRefresh();

        if (slot == SLOT_PREV_PAGE && currentPage > 0) {
            currentPage--;
            updateInventory();
            return;
        }

        if (slot == SLOT_NEXT_PAGE) {
            int totalPages = selection.totalPages();
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
                plugin.getTerminalSessions().promptSearch(player, this);
            }
            return;
        }

        if (slot == SLOT_SORT) {
            if (isShiftClick) {
                resetSortToDefault();
            } else {
                cycleSortType();
            }
            currentPage = 0;
            updateInventory();
            return;
        }

        if (slot == SLOT_STATS) {
            plugin.getTerminalSessions().openStats(player, network, this);
            return;
        }

        if (slot == SLOT_REFRESH) {
            forceScanNextUpdate = true;
            updateInventory();
            player.sendMessage(lang.getMessage("terminal.refreshed"));
            return;
        }

        if (slot >= 0 && slot < ITEMS_PER_PAGE) {
            if (slot < selection.items().size()) {
                Map.Entry<ItemStack, Integer> entry = selection.items().get(slot);
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
                    handleItemExtraction(originalItem, amountToTake);
                }
            }
        }
    }

    private void handleItemExtraction(ItemStack itemType, int amountToTake) {
        network.getMovement().withdrawToPlayer(player, itemType, amountToTake);
        requestRefresh();
    }

    private void prepareBulkDeposit() {
        pendingDeposit = BulkDepositPlan.capture(player.getInventory()::getItem, System.currentTimeMillis());
        if (pendingDeposit.isEmpty()) {
            pendingDeposit = null;
            player.sendMessage(lang.getMessage("terminal.bulk.nothing"));
        }
        requestRefresh();
    }

    public void cancelBulkDeposit() {
        if (pendingDeposit == null) return;
        pendingDeposit = null;
    }

    private void confirmBulkDeposit() {
        BulkDepositPlan plan = pendingDeposit;
        pendingDeposit = null;
        if (plan == null) return;
        if (!ensureAccess()) return;
        if (!plan.matches(player.getInventory()::getItem, System.currentTimeMillis())) {
            player.sendMessage(lang.getMessage("terminal.bulk.changed"));
            requestRefresh();
            return;
        }
        int deposited = 0;
        for (int slot : plan.slots()) {
            var result = network.getMovement().depositFromPlayer(player,
                    new com.dermoha.networkstorage.storage.NetworkMovement.ItemSource.InvSlotSource(player, slot));
            deposited += result.deposited();
        }
        if (deposited > 0) {
            player.sendMessage(String.format(lang.getMessage("terminal.deposit_all.summary"), deposited, plan.slots().size()));
        } else {
            player.sendMessage(lang.getMessage("terminal.bulk.no_space"));
        }
        requestRefresh();
    }

    private void cycleSortType() {
        sortType = SortType.values()[(sortType.ordinal() + 1) % SortType.values().length];
        plugin.getNetworkManager().setPlayerSortType(player, sortType);
        player.sendMessage(String.format(lang.getMessage("terminal.sort_changed"), getSortDisplayName()));
    }

    private void resetSortToDefault() {
        sortType = defaultSort;
        plugin.getNetworkManager().clearPlayerSortType(player);
        player.sendMessage(String.format(lang.getMessage("terminal.sort.reset"), getSortDisplayName()));
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

        pendingDeposit = null;
        updateInventory();
        player.openInventory(inventory);
        return true;
    }

    /** Collapses bursts of inventory events into one next-tick render. */
    public void requestRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        Bukkit.getScheduler().runTask(plugin, this::updateInventory);
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
