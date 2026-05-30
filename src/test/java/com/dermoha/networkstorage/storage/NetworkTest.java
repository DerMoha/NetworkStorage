package com.dermoha.networkstorage.storage;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkTest {

    @Test
    void ownerCanAccessPlayerNetwork() {
        UUID ownerId = UUID.randomUUID();
        Player owner = player(ownerId);
        Network network = new Network("Owned", ownerId, new FakeAccessRules(false, true));

        assertTrue(network.canAccess(owner));
    }

    @Test
    void trustedPlayerCanAccessWhenTrustSystemEnabled() {
        UUID trustedId = UUID.randomUUID();
        Player trusted = player(trustedId);
        Network network = new Network("Trusted", UUID.randomUUID(), new FakeAccessRules(false, true));
        network.addTrustedPlayer(trustedId);

        assertTrue(network.canAccess(trusted));
    }

    @Test
    void untrustedPlayerCannotAccessWhenTrustSystemEnabled() {
        Player untrusted = player(UUID.randomUUID());
        Network network = new Network("Private", UUID.randomUUID(), new FakeAccessRules(false, true));

        assertFalse(network.canAccess(untrusted));
    }

    @Test
    void untrustedPlayerCanAccessWhenTrustSystemDisabled() {
        Player untrusted = player(UUID.randomUUID());
        Network network = new Network("Open", UUID.randomUUID(), new FakeAccessRules(false, false));

        assertTrue(network.canAccess(untrusted));
    }

    @Test
    void globalNetworkModeAllowsAccess() {
        Player player = player(UUID.randomUUID());
        Network network = new Network("Global", UUID.randomUUID(), new FakeAccessRules(true, true));

        assertTrue(network.canAccess(player));
    }

    @Test
    void privilegedPlayerCanAccess() {
        Player admin = player(UUID.randomUUID());
        FakeAccessRules accessRules = new FakeAccessRules(false, true);
        accessRules.privileges.add("networkstorage.admin");
        Network network = new Network("Admin", UUID.randomUUID(), accessRules);

        assertTrue(network.canAccess(admin));
    }

    private Player player(UUID playerId) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("getUniqueId")) {
                return playerId;
            }
            if (method.getName().equals("toString")) {
                return "TestPlayer{" + playerId + "}";
            }
            if (method.getName().equals("hashCode")) {
                return playerId.hashCode();
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0.0f;
            }
            if (returnType == double.class) {
                return 0.0d;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        });
    }

    private static class FakeAccessRules implements NetworkAccessRules {
        private final boolean globalNetworkMode;
        private final boolean trustSystemEnabled;
        private final Set<String> privileges = new HashSet<>();

        private FakeAccessRules(boolean globalNetworkMode, boolean trustSystemEnabled) {
            this.globalNetworkMode = globalNetworkMode;
            this.trustSystemEnabled = trustSystemEnabled;
        }

        @Override
        public boolean isGlobalNetworkMode() {
            return globalNetworkMode;
        }

        @Override
        public boolean hasPrivilege(CommandSender sender, String permission) {
            return privileges.contains(permission);
        }

        @Override
        public boolean isTrustSystemEnabled() {
            return trustSystemEnabled;
        }
    }
}
