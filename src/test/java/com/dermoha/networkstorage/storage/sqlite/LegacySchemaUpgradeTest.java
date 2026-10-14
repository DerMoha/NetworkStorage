package com.dermoha.networkstorage.storage.sqlite;

import com.dermoha.networkstorage.storage.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacySchemaUpgradeTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void caseVariantsAndStoredDataSurviveEverySchemaUpgrade(int version) throws Exception {
        Path database = directory.resolve("networks.db");
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        SqliteNetworkStorageProvider.ensureDriver();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database);
             var st = c.createStatement()) {
            for (var migration : Migrations.all()) {
                if (migration.targetVersion() <= version) migration.migrate(c);
            }
            st.execute("PRAGMA user_version = " + version);
            st.execute("INSERT INTO networks VALUES ('Main', '" + owner + "', 'upper'), ('main', '" + trusted + "', 'lower')");
            st.execute("INSERT INTO network_chests VALUES ('Main', 'absent', 1, 64, 2)");
            st.execute("INSERT INTO network_trusted VALUES ('Main', '" + trusted + "', NULL)");
            st.execute("INSERT INTO network_stats VALUES ('Main', '" + owner + "', 'Alex', 4, 2)");
            st.execute("INSERT INTO player_state(player_uuid, selected_network, selected_wireless) VALUES ('" + owner + "', 'Main', 'main')");
        }
        StorageSnapshot original = null;
        for (int restart = 0; restart < 2; restart++) {
            SqliteNetworkStorageProvider provider = new SqliteNetworkStorageProvider(database.toFile());
            try {
                provider.initialize();
                assertEquals(Migrations.CURRENT_VERSION, provider.getSchemaVersion());
                Map<String, Network> networks = new HashMap<>();
                Map<UUID, String> selected = new HashMap<>(), wireless = new HashMap<>(), sorts = new HashMap<>();
                provider.loadNetworks(networks, selected, wireless, sorts);
                assertEquals(Set.of("Main", "main"), networks.keySet());
                assertEquals(owner, networks.get("Main").getOwner());
                assertEquals(trusted, networks.get("main").getOwner());
                assertEquals(Set.of(trusted), networks.get("Main").getTrustedPlayers());
                assertEquals(Set.of(new StoredLocation("absent", 1, 64, 2)), networks.get("Main").getUnloadedChestLocations());
                assertEquals(Map.of(owner, "Main"), selected);
                assertEquals(Map.of(owner, "main"), wireless);
                StorageSnapshot snapshot = StorageSnapshot.capture(networks.values(), selected, wireless, sorts);
                if (original == null) original = snapshot;
                else assertEquals(original, snapshot);
                provider.verifySnapshot(snapshot);
                assertTrue(provider.saveSnapshot(snapshot));
            } finally { provider.shutdown(); }
        }
    }
}
