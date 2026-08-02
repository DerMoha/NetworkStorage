package com.dermoha.networkstorage.storage.sqlite;

import com.dermoha.networkstorage.NetworkStoragePlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Creates verified, point-in-time SQLite backups and retains the newest three. */
public final class SqliteBackupManager {

    private static final int RETAINED_BACKUPS = 3;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final SqliteNetworkStorageProvider provider;
    private final File dataFolder;
    private final Logger logger;

    public SqliteBackupManager(NetworkStoragePlugin plugin, SqliteNetworkStorageProvider provider) {
        this.provider = provider;
        this.dataFolder = plugin.getDataFolder();
        this.logger = plugin.getLogger();
    }

    public SqliteBackupManager(SqliteNetworkStorageProvider provider, File dataFolder, Logger logger) {
        this.provider = provider;
        this.dataFolder = dataFolder;
        this.logger = logger == null ? Logger.getLogger(SqliteBackupManager.class.getName()) : logger;
    }

    public boolean createBackup() {
        File destination = new File(dataFolder,
                "networks.db.backup-" + LocalDateTime.now().format(TIMESTAMP) + "-" + UUID.randomUUID() + ".db");
        if (!provider.backupTo(destination)) {
            return false;
        }
        try {
            // backupTo already verifies the new copy; verify it again immediately before rotation
            // so no unverified file can cause an older known-good backup to be removed.
            if (!provider.verifyBackup(destination)) {
                logger.severe("SQLite backup verification failed; retaining existing backups");
                Files.deleteIfExists(destination.toPath());
                return false;
            }
            rotateBackups();
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not rotate SQLite backups", e);
            return true;
        }
    }

    private void rotateBackups() throws IOException {
        File[] backups = dataFolder.listFiles((directory, name) ->
                name.startsWith("networks.db.backup-") && name.endsWith(".db"));
        if (backups == null || backups.length <= RETAINED_BACKUPS) {
            return;
        }
        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed()
                .thenComparing(File::getName, Comparator.reverseOrder()));
        for (File backup : backups) {
            if (!provider.verifyBackup(backup)) {
                logger.warning("Skipping SQLite backup rotation because an existing copy failed verification: "
                        + backup.getName());
                return;
            }
        }
        for (int index = RETAINED_BACKUPS; index < backups.length; index++) {
            Files.deleteIfExists(backups[index].toPath());
        }
    }
}
