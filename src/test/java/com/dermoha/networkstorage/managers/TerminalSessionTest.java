package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.TestItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalSessionTest {
    // Rendering is outside this lifecycle; no Bukkit inventory is needed for these transitions.
    private final TerminalSession session = new TerminalSession();

    @Test
    void searchSurvivesTerminalCloseAndInputIsClaimedOnce() {
        session.opened(null);
        session.beginSearch(null);
        assertFalse(session.closeTerminal());
        assertTrue(session.isSearching());
        TerminalSession.SearchInput input = session.claimSearch();
        assertNotNull(input);
        assertFalse(session.isSearching());
        assertNull(session.claimSearch());
        assertTrue(session.acceptsSearchInput(input));
        assertFalse(session.expireSearch(), "claimed input wins over timeout");
        session.opened(null);
        assertFalse(session.acceptsSearchInput(input));
    }

    @Test
    void statsRoundTripPreservesPendingFinalUse() {
        session.pendingBreak(EquipmentSlot.HAND, new TestItemStack(Material.STICK, 1));
        session.opened(null);
        session.beginStats();
        assertFalse(session.closeTerminal());
        session.returnFromStats();
        assertFalse(session.closeStats());
        session.opened(null);
        assertTrue(session.closeTerminal());
        assertNotNull(session.takePendingBreak());
        assertNull(session.takePendingBreak());
    }

    @Test
    void closingStatsEndsTheSession() {
        session.beginStats();
        assertFalse(session.closeTerminal());
        assertTrue(session.closeStats());
        assertFalse(session.isSearching());
    }

    @Test
    void timeoutAndCancellationStopSearch() {
        session.beginSearch(null);
        session.closeTerminal();
        assertTrue(session.expireSearch());
        assertFalse(session.expireSearch());
        assertNull(session.claimSearch());
        session.beginSearch(null);
        session.cancelSearch();
        assertNull(session.claimSearch());
    }

    @Test
    void quitInvalidatesQueuedInputAndTaskIsCancelledOnce() {
        session.beginSearch(null);
        TerminalSession.SearchInput input = session.claimSearch();
        session.searchTask(12);
        session.close();
        assertFalse(session.acceptsSearchInput(input));
        assertEquals(12, session.takeSearchTask());
        assertEquals(-1, session.takeSearchTask());
    }

    @Test
    void earlierSearchInputCannotReopenANewerSearch() {
        session.beginSearch(null);
        TerminalSession.SearchInput oldInput = session.claimSearch();
        session.beginSearch(null);
        TerminalSession.SearchInput newInput = session.claimSearch();
        assertFalse(session.acceptsSearchInput(oldInput));
        assertTrue(session.acceptsSearchInput(newInput));
    }

    @Test
    void finalUseStoresACopyAndCanOnlyBeConsumedOnce() {
        TestItemStack item = new TestItemStack(Material.STICK, 1);
        session.pendingBreak(EquipmentSlot.OFF_HAND, item);
        item.setAmount(2);
        TerminalSession.PendingWirelessBreak pending = session.takePendingBreak();
        assertEquals(EquipmentSlot.OFF_HAND, pending.hand());
        assertEquals(1, pending.item().getAmount());
        assertNull(session.takePendingBreak());
    }
}
