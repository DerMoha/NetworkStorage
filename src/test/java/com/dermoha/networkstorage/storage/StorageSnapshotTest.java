package com.dermoha.networkstorage.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageSnapshotTest {
    @Test void allNestedListsAreImmutableAndDetached() {
        var locations = new ArrayList<>(List.of(new StorageSnapshot.LocationData("world", 1, 2, 3)));
        var trusts = new ArrayList<>(List.of(new StorageSnapshot.TrustedPlayer(UUID.randomUUID(), null)));
        var stats = new ArrayList<>(List.of(new StorageSnapshot.PlayerStatData(UUID.randomUUID(), "Alex", 1, 2)));
        var data = new StorageSnapshot.NetworkData("Test", UUID.randomUUID(), "", locations, locations, locations, trusts, stats);
        var snapshot = new StorageSnapshot(List.of(data), Map.of(), Map.of(), Map.of());
        String digest = snapshot.digest();
        locations.clear(); trusts.clear(); stats.clear();
        for (List<?> list : List.of(data.chests(), data.terminals(), data.senders(), data.trusted(), data.stats())) {
            assertEquals(1, list.size());
            assertThrows(UnsupportedOperationException.class, list::clear);
        }
        assertEquals(digest, snapshot.digest());
    }
}
