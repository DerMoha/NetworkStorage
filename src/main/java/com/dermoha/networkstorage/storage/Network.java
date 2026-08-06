package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.stats.PlayerStat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Network {

    public static final int MAX_DESCRIPTION_LENGTH = 128;

    private String name;
    private String description = "";
    private final Map<UUID, Long> trustedPlayersWithExpiry = new java.util.concurrent.ConcurrentHashMap<>();
    private final UUID owner;
    private final Set<Location> chestLocations;
    private final Set<Location> terminalLocations;
    private final Set<Location> senderChestLocations;
    private final Set<StoredLocation> unloadedChestLocations;
    private final Set<StoredLocation> unloadedTerminalLocations;
    private final Set<StoredLocation> unloadedSenderChestLocations;
    private final Map<UUID, PlayerStat> playerStats;
    private final Set<UUID> trustedPlayers;
    private final NetworkAccessRules accessRules;
    private final MovementEvents movementEvents;
    private final NetworkMovement movement;
    private transient boolean dirty = false;

    private transient volatile NetworkScanResult scanResult;
    private transient volatile NetworkScanResult lastCompleteScan;
    private transient volatile long contentVersion;
    private transient volatile Runnable contentChangeListener = () -> {};
    private final AtomicLong totalStoredAmount = new AtomicLong(0L);

    public Network(String name, UUID owner, NetworkAccessRules accessRules) {
        this(name, owner, accessRules, MovementEvents.NOOP);
    }

    public Network(String name, UUID owner, NetworkAccessRules accessRules, MovementEvents movementEvents) {
        this.name = name;
        this.owner = owner;
        this.accessRules = accessRules;
        this.movementEvents = movementEvents;
        this.movement = new NetworkMovement(this, movementEvents);
        // Bukkit locations may be null in a corrupt/partially-migrated
        // registration. Keep those entries so the scanner can report them as
        // INCOMPLETE instead of failing while loading the network.
        this.chestLocations = Collections.synchronizedSet(new HashSet<>());
        this.terminalLocations = Collections.synchronizedSet(new HashSet<>());
        this.senderChestLocations = Collections.synchronizedSet(new HashSet<>());
        this.unloadedChestLocations = ConcurrentHashMap.newKeySet();
        this.unloadedTerminalLocations = ConcurrentHashMap.newKeySet();
        this.unloadedSenderChestLocations = ConcurrentHashMap.newKeySet();
        this.playerStats = new ConcurrentHashMap<>();
        this.trustedPlayers = ConcurrentHashMap.newKeySet();
        this.scanResult = NetworkScanResult.pending(name, 0, 0, null);
    }

    public NetworkMovement getMovement() {
        return movement;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.dirty = true;
    }

    public String getDescription() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        if (description == null) {
            this.description = "";
        } else {
            this.description = description.length() > MAX_DESCRIPTION_LENGTH
                    ? description.substring(0, MAX_DESCRIPTION_LENGTH)
                    : description;
        }
        this.dirty = true;
    }

    public UUID getOwner() {
        return owner;
    }

    public Set<Location> getChestLocations() {
        synchronized (chestLocations) {
            return new HashSet<>(chestLocations);
        }
    }

    public Set<Location> getTerminalLocations() {
        synchronized (terminalLocations) {
            return new HashSet<>(terminalLocations);
        }
    }

    public Set<Location> getSenderChestLocations() {
        synchronized (senderChestLocations) {
            return new HashSet<>(senderChestLocations);
        }
    }

    public Set<StoredLocation> getUnloadedChestLocations() {
        return Set.copyOf(unloadedChestLocations);
    }

    public Set<StoredLocation> getUnloadedTerminalLocations() {
        return Set.copyOf(unloadedTerminalLocations);
    }

    public Set<StoredLocation> getUnloadedSenderChestLocations() {
        return Set.copyOf(unloadedSenderChestLocations);
    }

    public void addUnloadedChest(StoredLocation location) {
        unloadedChestLocations.add(Objects.requireNonNull(location));
    }

    public void addUnloadedTerminal(StoredLocation location) {
        unloadedTerminalLocations.add(Objects.requireNonNull(location));
    }

    public void addUnloadedSenderChest(StoredLocation location) {
        unloadedSenderChestLocations.add(Objects.requireNonNull(location));
    }

    /** Resolves positions for a newly loaded world without treating it as a content mutation. */
    public boolean resolveUnloadedLocations(World world) {
        boolean changed = resolveUnloaded(unloadedChestLocations, chestLocations, world);
        changed |= resolveUnloaded(unloadedTerminalLocations, terminalLocations, world);
        changed |= resolveUnloaded(unloadedSenderChestLocations, senderChestLocations, world);
        return changed;
    }

    public void clearUnloadedLocations() {
        unloadedChestLocations.clear();
        unloadedTerminalLocations.clear();
        unloadedSenderChestLocations.clear();
    }

    private boolean resolveUnloaded(Set<StoredLocation> unresolved, Set<Location> resolved, World world) {
        boolean changed = false;
        for (StoredLocation location : new ArrayList<>(unresolved)) {
            if (location.world().equals(world.getName()) && unresolved.remove(location)) {
                resolved.add(location.resolve(world));
                changed = true;
            }
        }
        return changed;
    }

    public Map<UUID, PlayerStat> getPlayerStats() {
        return playerStats;
    }

    public Set<UUID> getTrustedPlayers() {
        return trustedPlayers;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public void addChest(Location location) {
        if (chestLocations.add(location)) {
            this.dirty = true;
            markContentsChanged();
        }
    }

    public void removeChest(Location location) {
        if (chestLocations.remove(location)) {
            this.dirty = true;
            markContentsChanged();
        }
    }

    public void addTerminal(Location location) {
        terminalLocations.add(location);
        this.dirty = true;
    }

    public void removeTerminal(Location location) {
        terminalLocations.remove(location);
        this.dirty = true;
    }

    public void addSenderChest(Location location) {
        if (senderChestLocations.add(location)) {
            this.dirty = true;
            markContentsChanged();
        }
    }

    public void removeSenderChest(Location location) {
        if (senderChestLocations.remove(location)) {
            this.dirty = true;
            markContentsChanged();
        }
    }

    public boolean isChestInNetwork(Location location) {
        return chestLocations.contains(location);
    }

    public boolean isTerminalInNetwork(Location location) {
        return terminalLocations.contains(location);
    }

    public boolean isSenderChestInNetwork(Location location) {
        return senderChestLocations.contains(location);
    }

    public PlayerStat getPlayerStat(Player player) {
        return playerStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStat(player.getUniqueId(), player.getName()));
    }

    public void recordItemsDeposited(Player player, int amount) {
        getPlayerStat(player).addItemsDeposited(amount);
        this.dirty = true;
    }

    public void recordItemsWithdrawn(Player player, int amount) {
        getPlayerStat(player).addItemsWithdrawn(amount);
        this.dirty = true;
    }

    public boolean canAccess(Player player) {
        if (accessRules.isGlobalNetworkMode()) {
            return true;
        }
        UUID playerUUID = player.getUniqueId();
        if (playerUUID.equals(this.owner) || accessRules.hasPrivilege(player, "networkstorage.admin")) {
            return true;
        }
        if (accessRules.hasPrivilege(player, "networkstorage.access.all")) {
            return true;
        }
        if (accessRules.isTrustSystemEnabled()) {
            pruneExpiredTrusts();
            return trustedPlayers.contains(playerUUID);
        }
        return true;
    }

    public boolean isTrusted(UUID playerUUID) {
        return trustedPlayers.contains(playerUUID);
    }

    public void addTrustedPlayer(UUID playerUUID) {
        trustedPlayers.add(playerUUID);
        trustedPlayersWithExpiry.remove(playerUUID);
        this.dirty = true;
    }

    public void addTrustedPlayerWithExpiry(UUID playerUUID, long expiresAt) {
        trustedPlayers.add(playerUUID);
        trustedPlayersWithExpiry.put(playerUUID, expiresAt);
        this.dirty = true;
    }

    public void removeTrustedPlayer(UUID playerUUID) {
        trustedPlayers.remove(playerUUID);
        trustedPlayersWithExpiry.remove(playerUUID);
        this.dirty = true;
    }

    public Map<UUID, Long> getTrustedPlayersWithExpiry() {
        return new java.util.HashMap<>(trustedPlayersWithExpiry);
    }

    public void pruneExpiredTrusts() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        java.util.Iterator<java.util.Map.Entry<UUID, Long>> iterator = trustedPlayersWithExpiry.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                trustedPlayers.remove(entry.getKey());
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            this.dirty = true;
        }
    }

    public Map<ItemStack, Integer> getNetworkItems() {
        return getScanResult().items();
    }

    public void invalidateItemCache() {
        markContentsChanged();
    }

    public void setContentChangeListener(Runnable listener) {
        this.contentChangeListener = listener == null ? () -> {} : listener;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void beginScan(int registeredLocations, int uniqueChunks) {
        this.scanResult = NetworkScanResult.pending(name, registeredLocations, uniqueChunks, lastCompleteScan);
    }

    public void applyScanResult(NetworkScanResult result) {
        if (result == null) {
            return;
        }
        if (result.status() == NetworkScanStatus.PENDING) {
            scanResult = NetworkScanResult.pending(
                    name, result.registeredLocations(), result.uniqueChunks(), lastCompleteScan);
            return;
        }
        if (result.status() == NetworkScanStatus.COMPLETE && result.hasAuthoritativeData()) {
            lastCompleteScan = result;
            scanResult = result;
            setTotalStoredAmount(result.totalItems());
            return;
        }
        scanResult = NetworkScanResult.incomplete(
                name,
                result.registeredLocations(),
                result.uniqueChunks(),
                result.loadedChunks(),
                result.containersFound(),
                result.totalItems(),
                result.totalSlots(),
                result.usedSlots(),
                result.items(),
                result.warnings(),
                lastCompleteScan);
    }

    public NetworkScanResult getScanResult() {
        NetworkScanResult current = scanResult;
        return current == null ? NetworkScanResult.pending(name, chestLocations.size(), 0, lastCompleteScan) : current;
    }

    public NetworkScanResult getLastCompleteScan() {
        return lastCompleteScan;
    }

    public boolean hasCompleteScan() {
        return lastCompleteScan != null && getScanResult().status() == NetworkScanStatus.COMPLETE;
    }

    public boolean hasLastCompleteScan() {
        return lastCompleteScan != null;
    }

    public long getLastCompleteStoredAmount() {
        return lastCompleteScan == null ? 0L : lastCompleteScan.totalItems();
    }

    private void markContentsChanged() {
        contentVersion++;
        NetworkScanResult previous = lastCompleteScan;
        scanResult = NetworkScanResult.pending(name, chestLocations.size(),
                previous == null ? 0 : previous.uniqueChunks(), previous);
        contentChangeListener.run();
    }

    public long getTotalStoredAmount() {
        return totalStoredAmount.get();
    }

    public void adjustTotalStoredAmount(long delta) {
        totalStoredAmount.addAndGet(delta);
    }

    public void resetTotalStoredAmount() {
        totalStoredAmount.set(0L);
    }

    public void setTotalStoredAmount(long value) {
        totalStoredAmount.set(Math.max(0L, value));
    }

    public ItemStack removeFromNetwork(ItemStack itemToRemove, int amount) {
        requirePrimaryThread();
        if (amount < 0) {
            throw new IllegalArgumentException("Amount cannot be negative: " + amount);
        }
        int remaining = amount;

        for (Location chestLoc : getLoadedChestLocations()) {
            if (remaining <= 0) break;
            if (chestLoc.getBlock().getState() instanceof Container container) {
                Inventory inv = container.getInventory();
                for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.isSimilar(itemToRemove)) {
                        int toRemove = Math.min(remaining, item.getAmount());
                        if (item.getAmount() <= toRemove) {
                            inv.setItem(i, null);
                        } else {
                            item.setAmount(item.getAmount() - toRemove);
                        }
                        remaining -= toRemove;
                    }
                }
            }
        }
        int actuallyRemoved = amount - remaining;
        if (actuallyRemoved > 0) {
            adjustTotalStoredAmount(-actuallyRemoved);
        }
        invalidateItemCache();
        ItemStack result = itemToRemove.clone();
        result.setAmount(actuallyRemoved);
        return result;
    }

    public ItemStack addToNetwork(ItemStack itemToAdd) {
        requirePrimaryThread();
        if (itemToAdd == null || itemToAdd.getType() == Material.AIR) return null;
        ItemStack remaining = itemToAdd.clone();
        for (Location chestLoc : getLoadedChestLocations()) {
            if (remaining.getAmount() <= 0) break;
            if (chestLoc.getBlock().getState() instanceof Container container) {
                HashMap<Integer, ItemStack> result = container.getInventory().addItem(remaining);
                if (result.isEmpty()) {
                    remaining = null;
                    break;
                } else {
                    remaining = result.get(0);
                }
            }
        }
        int originalAmount = itemToAdd.getAmount();
        int added = remaining == null ? originalAmount : originalAmount - remaining.getAmount();
        if (added > 0) {
            adjustTotalStoredAmount(added);
        }
        invalidateItemCache();
        return remaining;
    }

    public double getCapacityPercent() {
        return getScanResult().capacityPercent();
    }

    private List<Location> getLoadedChestLocations() {
        Map<ChunkKey, Boolean> loadedChunks = new HashMap<>();
        List<Location> loadedLocations = new ArrayList<>();
        for (Location location : getChestLocations()) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            ChunkKey key = new ChunkKey(location.getWorld().getUID(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
            boolean loaded = loadedChunks.computeIfAbsent(key, ignored -> {
                try {
                    World world = location.getWorld();
                    if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                        world.loadChunk(key.chunkX(), key.chunkZ(), false);
                    }
                    return world.isChunkLoaded(key.chunkX(), key.chunkZ());
                } catch (RuntimeException ignoredException) {
                    return false;
                }
            });
            if (loaded) {
                loadedLocations.add(location);
            }
        }
        return loadedLocations;
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Network storage world and inventory access must run on the Bukkit main thread");
        }
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }
}
