package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.gui.TerminalGUI;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** One player's terminal lifecycle. Search input may be claimed by an async chat event. */
final class TerminalSession<T> {
    enum Phase { CLOSED, TERMINAL, TO_SEARCH, SEARCH, SEARCH_INPUT, TO_STATS, STATS, FROM_STATS }

    private Phase phase = Phase.CLOSED;
    private T terminal;
    private long searchGeneration;
    private int searchTaskId = -1;
    private PendingWirelessBreak pendingBreak;

    synchronized void opened(T terminal) {
        this.terminal = terminal;
        phase = Phase.TERMINAL;
    }

    synchronized T visibleTerminal() {
        return phase == Phase.TERMINAL ? terminal : null;
    }

    synchronized void beginSearch(T terminal) {
        this.terminal = terminal;
        searchGeneration++;
        phase = Phase.TO_SEARCH;
    }

    synchronized boolean isSearching() {
        return phase == Phase.TO_SEARCH || phase == Phase.SEARCH;
    }

    synchronized SearchInput<T> claimSearch() {
        if (!isSearching()) return null;
        phase = Phase.SEARCH_INPUT;
        return new SearchInput<>(terminal, searchGeneration);
    }

    synchronized boolean acceptsSearchInput(SearchInput<T> input) {
        return phase == Phase.SEARCH_INPUT && input.generation() == searchGeneration;
    }

    synchronized void cancelSearch() {
        if (isSearching() || phase == Phase.SEARCH_INPUT) close();
    }

    synchronized boolean expireSearch() {
        if (!isSearching()) return false;
        close();
        return true;
    }

    synchronized void beginStats() {
        phase = Phase.TO_STATS;
    }

    synchronized void returnFromStats() {
        phase = Phase.FROM_STATS;
    }

    /** Returns true only when this close ends the session. */
    synchronized boolean closeTerminal(T closing) {
        if (terminal != closing) return false;
        return closeTerminal();
    }

    synchronized boolean closeTerminal() {
        if (phase == Phase.TO_SEARCH) {
            phase = Phase.SEARCH;
            return false;
        }
        if (phase == Phase.SEARCH_INPUT) return false;
        if (phase == Phase.TO_STATS) {
            phase = Phase.STATS;
            return false;
        }
        close();
        return true;
    }

    synchronized boolean closeStats() {
        if (phase == Phase.FROM_STATS) {
            phase = Phase.CLOSED;
            return false;
        }
        close();
        return true;
    }

    synchronized void close() {
        terminal = null;
        phase = Phase.CLOSED;
    }

    synchronized void searchTask(int taskId) {
        searchTaskId = taskId;
    }

    synchronized int takeSearchTask() {
        int taskId = searchTaskId;
        searchTaskId = -1;
        return taskId;
    }

    synchronized void pendingBreak(EquipmentSlot hand, ItemStack item) {
        pendingBreak = new PendingWirelessBreak(hand, item.clone());
    }

    synchronized PendingWirelessBreak takePendingBreak() {
        PendingWirelessBreak pending = pendingBreak;
        pendingBreak = null;
        return pending;
    }

    record SearchInput<T>(T terminal, long generation) { }

    record PendingWirelessBreak(EquipmentSlot hand, ItemStack item) { }
}
