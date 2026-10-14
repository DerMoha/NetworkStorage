package com.dermoha.networkstorage.gui;

import java.util.List;

/** A clamped view of the current selector entries after access changes. */
record SelectionPage<T>(List<T> entries, int index, boolean hasPrevious, boolean hasNext) {
    static final int SIZE = 45;

    static <T> SelectionPage<T> of(List<T> entries, int requestedPage) {
        int lastPage = Math.max(0, (entries.size() - 1) / SIZE);
        int index = Math.max(0, Math.min(requestedPage, lastPage));
        int first = index * SIZE;
        return new SelectionPage<>(List.copyOf(entries.subList(first, Math.min(first + SIZE, entries.size()))),
                index, index > 0, index < lastPage);
    }
}
