package com.dermoha.networkstorage.commands;

import org.bukkit.command.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedCommandTest {
    @Test
    void allRootsAndHelpFormsUseTheSameHelpWithoutAdminPermission() {
        List<String> calls = new ArrayList<>();
        UnifiedCommand router = router(calls, false);
        for (String root : List.of("ns", "storage", "network", "networkstorage", "net", "netstorage")) {
            for (String[] args : List.of(new String[0], new String[] {"help"}, new String[] {"storage"},
                    new String[] {"storage", "help"}, new String[] {"network", "help"}, new String[] {"admin", "help"})) {
                router.onCommand(null, null, root, args);
                assertEquals(List.of("help"), calls);
                calls.clear();
            }
        }
    }

    @Test
    void routesPlayerNetworkAndAdminActionsWithoutConfusingInfoAndList() {
        List<String> calls = new ArrayList<>();
        UnifiedCommand router = router(calls, true);
        router.onCommand(null, null, "ns", new String[] {"wireless"});
        router.onCommand(null, null, "networkstorage", new String[] {"info"});
        router.onCommand(null, null, "ns", new String[] {"select", "Main"});
        router.onCommand(null, null, "ns", new String[] {"list"});
        router.onCommand(null, null, "ns", new String[] {"admin", "info", "Main"});
        router.onCommand(null, null, "ns", new String[] {"admin", "list"});
        router.onCommand(null, null, "networkstorage", new String[] {"reload"});
        assertEquals(List.of("player:wireless", "player:info", "network:select Main", "network:list",
                "admin:info Main", "admin:list", "admin:reload"), calls);
    }

    @Test
    void completionsAreConsistentAndAdminOptionsAreNotAdvertisedToPlayers() {
        List<String> calls = new ArrayList<>();
        UnifiedCommand router = router(calls, false);
        List<String> root = router.onTabComplete(null, null, "ns", new String[] {""});
        assertTrue(root.containsAll(List.of("help", "wireless", "select", "list")));
        assertFalse(root.contains("admin"));
        assertEquals(root, router.onTabComplete(null, null, "networkstorage", new String[] {""}));
        assertEquals(root, router.onTabComplete(null, null, "network", new String[] {"storage", ""}));
        assertTrue(router.onTabComplete(null, null, "ns", new String[] {"admin", ""}).isEmpty());
        assertTrue(router(calls, true).onTabComplete(null, null, "ns", new String[] {"admin", ""}).contains("storage"));
    }

    private UnifiedCommand router(List<String> calls, boolean admin) {
        CommandExecutor playerHandler = (sender, command, label, args) -> { calls.add("player:" + String.join(" ", args)); return true; };
        CommandExecutor networkHandler = (sender, command, label, args) -> { calls.add("network:" + String.join(" ", args)); return true; };
        CommandExecutor adminHandler = (sender, command, label, args) -> { calls.add("admin:" + String.join(" ", args)); return true; };
        TabCompleter tabs = (sender, command, label, args) -> List.of();
        return new UnifiedCommand(playerHandler, networkHandler, adminHandler, tabs, tabs, sender -> calls.add("help"), sender -> admin);
    }
}
