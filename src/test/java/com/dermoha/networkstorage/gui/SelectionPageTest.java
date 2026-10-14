package com.dermoha.networkstorage.gui;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SelectionPageTest {
    @Test void everyNetworkIsReachableAtCapacityBoundaries() {
        for (int count : new int[]{0, 1, 45, 46, 54, 55, 136}) {
            List<Integer> entries = IntStream.range(0, count).boxed().toList();
            java.util.ArrayList<Integer> visited = new java.util.ArrayList<>();
            int page = 0;
            SelectionPage<Integer> selection;
            do {
                selection = SelectionPage.of(entries, page++);
                visited.addAll(selection.entries());
                assertEquals(selection.index() > 0, selection.hasPrevious());
            } while (selection.hasNext());
            assertEquals(entries, visited);
        }
    }

    @Test void accessChangesClampPageAndSnapshotsAreDetached() {
        var entries = new java.util.ArrayList<>(IntStream.range(0, 55).boxed().toList());
        var oldPage = SelectionPage.of(entries, 1);
        entries.clear();
        assertEquals(10, oldPage.entries().size());
        var currentPage = SelectionPage.of(entries, oldPage.index());
        assertEquals(0, currentPage.index());
        assertTrue(currentPage.entries().isEmpty());
        assertFalse(currentPage.hasNext());
        assertFalse(currentPage.hasPrevious());
        assertEquals(0, SelectionPage.of(List.of(1), -1).index());
    }
}
