package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.StoredLocation;
import com.dermoha.networkstorage.TestItemStack;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkContentScannerTest {

    @Test
    void rejectsWorldAccessOffTheMainThread() {
        NetworkContentScanner scanner = new NetworkContentScanner(() -> false);

        assertThrows(IllegalStateException.class, () -> scanner.begin("Global", List.of()));
    }

    @Test
    void loadsEachFarAwayChunkOnceAndProcessesIncrementally() {
        FakeWorld fakeWorld = new FakeWorld("remote", UUID.randomUUID());
        fakeWorld.addContainer(1000, 64, 10, 27, new TestItemStack(Material.DIAMOND, 12));
        fakeWorld.addContainer(1005, 64, 10, 27, new TestItemStack(Material.DIAMOND, 8));
        fakeWorld.addContainer(1160, 64, 10, 27, new TestItemStack(Material.GOLD_INGOT, 32));
        fakeWorld.addContainer(1320, 64, 10, 27, new TestItemStack(Material.IRON_INGOT, 47_260));
        List<Location> locations = fakeWorld.locations();

        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin("Global", locations);

        assertEquals(4, session.registeredLocations());
        assertEquals(3, session.uniqueChunks(), "two containers in one chunk must be grouped");
        assertEquals(0, fakeWorld.loadCalls);

        NetworkContentScanner.ScanStep first = scanner.advance(session, 2);
        assertFalse(first.complete());
        assertEquals(2, fakeWorld.loadCalls, "the first tick should load two unique chunks");

        NetworkContentScanner.ScanStep second = scanner.advance(session, 2);
        assertTrue(second.complete());
        NetworkScanResult result = second.result();
        assertNotNull(result);
        assertEquals(3, fakeWorld.loadCalls);
        assertEquals(3, result.uniqueChunks());
        assertEquals(3, result.loadedChunks());
        assertEquals(4, result.containersFound());
        assertEquals(47_312L, result.totalItems());
        assertEquals(3, result.uniqueTypes());
        assertEquals(0, result.warnings().size());
        assertTrue(result.hasAuthoritativeData());
        assertEquals(1, fakeWorld.loadCallsForChunk(62, 0));
    }

    @Test
    void emptyNetworkIsACompleteZeroScan() {
        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin("Empty", List.of());
        NetworkContentScanner.ScanStep step = scanner.advance(session, 4);

        assertTrue(step.complete());
        assertEquals(com.dermoha.networkstorage.storage.NetworkScanStatus.COMPLETE, step.result().status());
        assertEquals(0L, step.result().totalItems());
        assertTrue(step.result().hasAuthoritativeData());
    }

    @Test
    void invalidContainerProducesIncompleteResultAndWarning() {
        FakeWorld fakeWorld = new FakeWorld("broken", UUID.randomUUID());
        fakeWorld.addInvalidBlock(400, 64, 10);

        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin("Global", fakeWorld.locations());
        NetworkContentScanner.ScanStep step = scanner.advance(session, 4);

        assertTrue(step.complete());
        assertEquals(com.dermoha.networkstorage.storage.NetworkScanStatus.INCOMPLETE, step.result().status());
        assertEquals(0, step.result().containersFound());
        assertFalse(step.result().warnings().isEmpty());
        assertFalse(step.result().hasAuthoritativeData());
    }

    @Test
    void unloadableChunkProducesIncompleteResult() {
        FakeWorld fakeWorld = new FakeWorld("unloadable", UUID.randomUUID());
        fakeWorld.addContainer(50, 64, 50, 27, new TestItemStack(Material.DIAMOND, 3));
        fakeWorld.rejectChunkLoad(3, 3);

        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin("Global", fakeWorld.locations());
        NetworkContentScanner.ScanStep step = scanner.advance(session, 4);

        assertEquals(com.dermoha.networkstorage.storage.NetworkScanStatus.INCOMPLETE, step.result().status());
        assertEquals(0, step.result().loadedChunks());
        assertEquals(0, step.result().containersFound());
        assertFalse(step.result().warnings().isEmpty());
    }

    @Test
    void missingWorldIsReportedAsIncompleteWithoutWorldAccess() {
        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin(
                "Global", List.of(new Location(null, 1, 64, 1)));
        NetworkContentScanner.ScanStep step = scanner.advance(session, 4);

        assertTrue(step.complete());
        assertEquals(com.dermoha.networkstorage.storage.NetworkScanStatus.INCOMPLETE, step.result().status());
        assertEquals(1, step.result().registeredLocations());
        assertEquals(0, step.result().uniqueChunks());
        assertFalse(step.result().warnings().isEmpty());
    }

    @Test
    void unloadedStoredWorldIsReportedAsIncomplete() {
        NetworkContentScanner scanner = new NetworkContentScanner(() -> true);
        NetworkContentScanner.ScanSession session = scanner.begin(
                "Remote", List.of(), List.of(new StoredLocation("season_two", 1, 64, 1)));
        NetworkContentScanner.ScanStep step = scanner.advance(session, 4);

        assertEquals(com.dermoha.networkstorage.storage.NetworkScanStatus.INCOMPLETE, step.result().status());
        assertEquals(1, step.result().registeredLocations());
        assertTrue(step.result().warnings().get(0).contains("season_two"));
    }

    private static final class FakeWorld {
        private final UUID uid;
        private final String name;
        private final Map<BlockKey, Block> blocks = new HashMap<>();
        private final Set<ChunkKey> loaded = ConcurrentHashMap.newKeySet();
        private final Set<ChunkKey> unloadable = ConcurrentHashMap.newKeySet();
        private final Map<ChunkKey, Integer> loadCallsByChunk = new HashMap<>();
        private int loadCalls;
        private final World world;

        private FakeWorld(String name, UUID uid) {
            this.name = name;
            this.uid = uid;
            this.world = proxy(World.class, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getUID" -> this.uid;
                    case "getName" -> this.name;
                    case "isChunkLoaded" -> this.loaded.contains(new ChunkKey((int) args[0], (int) args[1]));
                    case "loadChunk" -> {
                        int x = (int) args[0];
                        int z = (int) args[1];
                        ChunkKey key = new ChunkKey(x, z);
                        this.loadCalls++;
                        this.loadCallsByChunk.merge(key, 1, Integer::sum);
                        if (this.unloadable.contains(key)) {
                            yield false;
                        }
                        this.loaded.add(key);
                        yield true;
                    }
                    case "getBlockAt" -> {
                        int x;
                        int y;
                        int z;
                        if (args.length == 1 && args[0] instanceof Location location) {
                            x = location.getBlockX();
                            y = location.getBlockY();
                            z = location.getBlockZ();
                        } else {
                            x = (int) args[0];
                            y = (int) args[1];
                            z = (int) args[2];
                        }
                        yield this.blocks.getOrDefault(new BlockKey(x, y, z), emptyBlock());
                    }
                    default -> defaultValue(method.getReturnType());
                };
            });
        }

        private void addContainer(int x, int y, int z, int size, ItemStack... contents) {
            Inventory inventory = proxy(Inventory.class, (proxy, method, args) -> switch (method.getName()) {
                case "getSize" -> size;
                case "getContents" -> contents.clone();
                default -> defaultValue(method.getReturnType());
            });
            addBlock(x, y, z, containerBlock(inventory));
        }

        private void addInvalidBlock(int x, int y, int z) {
            addBlock(x, y, z, proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
                case "getState" -> proxy(BlockState.class, (state, stateMethod, stateArgs) -> defaultValue(stateMethod.getReturnType()));
                case "getType" -> Material.STONE;
                default -> defaultValue(method.getReturnType());
            }));
        }

        private void rejectChunkLoad(int chunkX, int chunkZ) {
            unloadable.add(new ChunkKey(chunkX, chunkZ));
        }

        private void addBlock(int x, int y, int z, Block block) {
            blocks.put(new BlockKey(x, y, z), block);
        }

        private Block containerBlock(Inventory inventory) {
            Container container = proxy(Container.class, (proxy, method, args) -> switch (method.getName()) {
                case "getInventory" -> inventory;
                default -> defaultValue(method.getReturnType());
            });
            return proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
                case "getState" -> container;
                case "getType" -> Material.CHEST;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Block emptyBlock() {
            return proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
                case "getState" -> proxy(BlockState.class, (state, stateMethod, stateArgs) -> defaultValue(stateMethod.getReturnType()));
                case "getType" -> Material.AIR;
                default -> defaultValue(method.getReturnType());
            });
        }

        private List<Location> locations() {
            List<Location> locations = new ArrayList<>();
            for (BlockKey key : blocks.keySet()) {
                locations.add(new Location(world, key.x(), key.y(), key.z()));
            }
            return locations;
        }

        private int loadCallsForChunk(int x, int z) {
            return loadCallsByChunk.getOrDefault(new ChunkKey(x, z), 0);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> type.getSimpleName() + "Proxy";
                            default -> null;
                        };
                    }
                    return handler.invoke(proxy, method, args);
                }));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0d;
        if (returnType == char.class) return '\0';
        return null;
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record ChunkKey(int x, int z) {
    }
}
