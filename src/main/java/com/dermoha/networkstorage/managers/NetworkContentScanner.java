package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.StoredLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Main-thread-only scanner for registered network containers.  The scanner
 * owns the only full traversal used by terminal views, admin information, and
 * the stored-item aggregate.
 */
public final class NetworkContentScanner {

    public static final int DEFAULT_CHUNKS_PER_TICK = 4;

    private final BooleanSupplier primaryThread;

    public NetworkContentScanner() {
        this(Bukkit::isPrimaryThread);
    }

    NetworkContentScanner(BooleanSupplier primaryThread) {
        this.primaryThread = primaryThread;
    }

    public ScanSession begin(String networkName, Collection<Location> locations) {
        return begin(networkName, locations, List.of());
    }

    public ScanSession begin(String networkName,
                             Collection<Location> locations,
                             Collection<StoredLocation> unloadedLocations) {
        requirePrimaryThread();
        Map<ChunkKey, ChunkGroup> grouped = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int registeredLocations = (locations == null ? 0 : locations.size())
                + (unloadedLocations == null ? 0 : unloadedLocations.size());

        if (unloadedLocations != null) {
            for (StoredLocation location : unloadedLocations) {
                warnings.add(networkName + ": registered location waits for world '"
                        + location.world() + "' to load: " + location.x() + ","
                        + location.y() + "," + location.z());
            }
        }

        if (locations != null) {
            for (Location location : locations) {
                if (location == null || location.getWorld() == null) {
                    warnings.add(networkName + ": registered location has no loaded world: " + location);
                    continue;
                }
                World world = location.getWorld();
                ChunkKey key = new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
                grouped.computeIfAbsent(key, ignored -> new ChunkGroup(world, key.chunkX(), key.chunkZ()))
                        .locations().add(location);
            }
        }

        return new ScanSession(networkName, registeredLocations, grouped, warnings);
    }

    public ScanStep advance(ScanSession session, int maxChunks) {
        requirePrimaryThread();
        if (session.complete()) {
            return new ScanStep(true, session.finish());
        }

        int budget = Math.max(1, maxChunks);
        int processed = 0;
        while (session.cursor() < session.groups().size() && processed < budget) {
            ChunkGroup group = session.groups().get(session.cursor());
            processChunk(session, group);
            session.advanceCursor();
            processed++;
        }

        if (session.cursor() < session.groups().size()) {
            return new ScanStep(false, null);
        }

        session.markComplete();
        return new ScanStep(true, session.finish());
    }

    private void processChunk(ScanSession session, ChunkGroup group) {
        boolean loaded;
        try {
            loaded = group.world().isChunkLoaded(group.chunkX(), group.chunkZ());
            if (!loaded) {
                loaded = group.world().loadChunk(group.chunkX(), group.chunkZ(), false);
            }
            loaded = loaded || group.world().isChunkLoaded(group.chunkX(), group.chunkZ());
        } catch (RuntimeException exception) {
            session.warn(chunkWarning(session, group) + ": " + messageOf(exception));
            return;
        }

        if (!loaded) {
            session.warn(chunkWarning(session, group));
            return;
        }
        session.incrementLoadedChunks();

        for (Location location : group.locations()) {
            try {
                if (!(location.getBlock().getState() instanceof Container container)) {
                    session.warn(session.networkName() + ": registered location is no longer a container: " + formatLocation(location));
                    continue;
                }
                session.incrementContainersFound();
                Inventory inventory = container.getInventory();
                session.addSlots(inventory.getSize());
                for (ItemStack item : inventory.getContents()) {
                    // Inventory contents cannot contain block-only air variants
                    // as real stacks; equality also avoids invoking registry
                    // lookups while the API is being used by lightweight tests.
                    if (item == null || item.getType() == org.bukkit.Material.AIR) {
                        continue;
                    }
                    session.incrementUsedSlots();
                    session.addItems(item);
                }
            } catch (RuntimeException exception) {
                session.warn(session.networkName() + ": could not read registered container "
                        + formatLocation(location) + ": " + messageOf(exception));
            }
        }
    }

    private void requirePrimaryThread() {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Network storage world and chunk access must run on the Bukkit main thread");
        }
    }

    private static String formatLocation(Location location) {
        if (location == null) {
            return "null";
        }
        String world = location.getWorld() == null ? "<unloaded-world>" : location.getWorld().getName();
        return world + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private static String chunkWarning(ScanSession session, ChunkGroup group) {
        String locations = group.locations().stream()
                .map(NetworkContentScanner::formatLocation)
                .toList()
                .toString();
        return session.networkName() + ": could not load chunk "
                + group.world().getName() + " " + group.chunkX() + "," + group.chunkZ()
                + " for registered locations " + locations;
    }

    private static String messageOf(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ChunkGroup(World world, int chunkX, int chunkZ, List<Location> locations) {
        private ChunkGroup(World world, int chunkX, int chunkZ) {
            this(world, chunkX, chunkZ, new ArrayList<>());
        }
    }

    public record ScanStep(boolean complete, NetworkScanResult result) {
    }

    public static final class ScanSession {
        private final String networkName;
        private final int registeredLocations;
        private final List<ChunkGroup> groups;
        private final Map<ItemStack, Integer> items = new LinkedHashMap<>();
        private final List<String> warnings;
        private int cursor;
        private int loadedChunks;
        private int containersFound;
        private long totalItems;
        private long totalSlots;
        private long usedSlots;
        private boolean complete;

        private ScanSession(String networkName,
                            int registeredLocations,
                            Map<ChunkKey, ChunkGroup> grouped,
                            List<String> warnings) {
            this.networkName = networkName;
            this.registeredLocations = registeredLocations;
            this.groups = new ArrayList<>(grouped.values());
            this.warnings = new ArrayList<>(warnings);
        }

        public String networkName() {
            return networkName;
        }

        public int registeredLocations() {
            return registeredLocations;
        }

        public int uniqueChunks() {
            return groups.size();
        }

        public int loadedChunks() {
            return loadedChunks;
        }

        public int containersFound() {
            return containersFound;
        }

        public int processedChunks() {
            return cursor;
        }

        public boolean complete() {
            return complete;
        }

        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        private int cursor() {
            return cursor;
        }

        private List<ChunkGroup> groups() {
            return groups;
        }

        private void advanceCursor() {
            cursor++;
        }

        private void markComplete() {
            complete = true;
        }

        private void incrementLoadedChunks() {
            loadedChunks++;
        }

        private void incrementContainersFound() {
            containersFound++;
        }

        private void incrementUsedSlots() {
            usedSlots++;
        }

        private void addSlots(int amount) {
            totalSlots += Math.max(0, amount);
        }

        private void addItems(ItemStack item) {
            totalItems += item.getAmount();
            ItemStack key = item.clone();
            key.setAmount(1);
            items.merge(key, item.getAmount(), Integer::sum);
        }

        private void warn(String warning) {
            warnings.add(warning);
        }

        private NetworkScanResult finish() {
            boolean completeScan = warnings.isEmpty() && containersFound == registeredLocations;
            if (completeScan) {
                return NetworkScanResult.complete(
                        networkName,
                        registeredLocations,
                        groups.size(),
                        loadedChunks,
                        containersFound,
                        totalItems,
                        totalSlots,
                        usedSlots,
                        items,
                        System.currentTimeMillis());
            }
            return new NetworkScanResult(
                    networkName,
                    com.dermoha.networkstorage.storage.NetworkScanStatus.INCOMPLETE,
                    registeredLocations,
                    groups.size(),
                    loadedChunks,
                    containersFound,
                    totalItems,
                    items.size(),
                    totalSlots,
                    usedSlots,
                    totalSlots > 0 ? (double) usedSlots * 100.0 / totalSlots : 0.0,
                    items,
                    false,
                    warnings,
                    System.currentTimeMillis());
        }
    }
}
