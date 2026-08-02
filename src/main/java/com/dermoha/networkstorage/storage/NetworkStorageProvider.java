package com.dermoha.networkstorage.storage;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface NetworkStorageProvider {

    void initialize();

    void shutdown();

    String getBackendName();

    int getSchemaVersion();

    boolean isEmpty();

    boolean isAvailable();

    void loadNetworks(Map<String, Network> networks,
                      Map<UUID, String> selectedNetworks,
                      Map<UUID, String> selectedWirelessNetworks);

    boolean saveSnapshot(Collection<Network> networks,
                         Map<UUID, String> selectedNetworks,
                         Map<UUID, String> selectedWirelessNetworks);

    default boolean saveSnapshot(StorageSnapshot snapshot) {
        throw new UnsupportedOperationException("Detached snapshots are not supported by this provider");
    }

    Map<String, Object> snapshot();
}
