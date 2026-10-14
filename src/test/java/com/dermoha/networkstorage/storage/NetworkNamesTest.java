package com.dermoha.networkstorage.storage;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NetworkNamesTest {
    private Network network(String name) {
        return new Network(name, UUID.randomUUID(), new NetworkAccessRules() {
            public boolean isGlobalNetworkMode() { return false; }
            public boolean hasPrivilege(org.bukkit.command.CommandSender sender, String permission) { return false; }
            public boolean isTrustSystemEnabled() { return true; }
        });
    }

    @Test
    void exactNamesWinAndAmbiguousFallbackFailsClosed() {
        Network upper = network("Main");
        Network lower = network("main");
        for (var networks : List.of(List.of(upper, lower), List.of(lower, upper))) {
            assertSame(upper, NetworkNames.find(networks, "Main"));
            assertSame(lower, NetworkNames.find(networks, "main"));
            assertNull(NetworkNames.find(networks, "MAIN"));
            assertNull(NetworkNames.find(networks, null));
            assertTrue(NetworkNames.conflicts(networks, "MAIN", null));
            assertTrue(NetworkNames.conflicts(networks, "MAIN", upper));
            assertFalse(NetworkNames.conflicts(networks, "Other", upper));
        }
        assertSame(upper, NetworkNames.find(List.of(upper), "MAIN"));
        assertFalse(NetworkNames.conflicts(List.of(upper), "MAIN", upper));
    }
}
