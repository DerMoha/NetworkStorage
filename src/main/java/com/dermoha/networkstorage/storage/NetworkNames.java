package com.dermoha.networkstorage.storage;

import java.util.Collection;

/** Exact names distinguish legacy networks; ambiguous case-insensitive requests fail closed. */
public final class NetworkNames {
    private NetworkNames() {}

    public static Network find(Collection<Network> networks, String name) {
        if (name == null) return null;
        for (Network network : networks) {
            if (network.getName().equals(name)) return network;
        }
        Network match = null;
        for (Network network : networks) {
            if (network.getName().equalsIgnoreCase(name)) {
                if (match != null) return null;
                match = network;
            }
        }
        return match;
    }

    public static boolean conflicts(Collection<Network> networks, String name, Network excluded) {
        return networks.stream().anyMatch(network -> network != excluded && network.getName().equalsIgnoreCase(name));
    }
}
