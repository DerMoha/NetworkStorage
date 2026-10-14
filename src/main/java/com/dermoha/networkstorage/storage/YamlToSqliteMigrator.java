package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.stats.PlayerStat;
import com.dermoha.networkstorage.storage.sqlite.SqliteNetworkStorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class YamlToSqliteMigrator {

    private final NetworkStoragePlugin plugin;
    private final NetworkAccessRules accessRules;
    private final MovementEvents movementEvents;
    private final File dataFolder;
    private final Logger logger;
    private final File yamlNetworksFile;
    private final File yamlPlayerStateFile;
    private final File targetDatabaseFile;
    public YamlToSqliteMigrator(NetworkStoragePlugin plugin,
                                File yamlNetworksFile,
                                File yamlPlayerStateFile,
                                File targetDatabaseFile) {
        this.plugin = plugin;
        this.accessRules = plugin.getConfigManager();
        this.movementEvents = plugin.getMovementEvents();
        this.dataFolder = plugin.getDataFolder();
        this.logger = plugin.getLogger();
        this.yamlNetworksFile = yamlNetworksFile;
        this.yamlPlayerStateFile = yamlPlayerStateFile;
        this.targetDatabaseFile = targetDatabaseFile;
    }

    /** Constructor used by migration tests and offline migration tooling. */
    public YamlToSqliteMigrator(NetworkAccessRules accessRules,
                                MovementEvents movementEvents,
                                File dataFolder,
                                File yamlNetworksFile,
                                File yamlPlayerStateFile,
                                File targetDatabaseFile,
                                Logger logger) {
        this.plugin = null;
        this.accessRules = accessRules;
        this.movementEvents = movementEvents;
        this.dataFolder = dataFolder;
        this.logger = logger == null ? Logger.getLogger(YamlToSqliteMigrator.class.getName()) : logger;
        this.yamlNetworksFile = yamlNetworksFile;
        this.yamlPlayerStateFile = yamlPlayerStateFile;
        this.targetDatabaseFile = targetDatabaseFile;
    }

    public void migrateYaml() {
        if ((!yamlNetworksFile.exists() || yamlNetworksFile.length() == 0)
                && (!yamlPlayerStateFile.exists() || yamlPlayerStateFile.length() == 0)) {
            return;
        }
        try {
            StorageSnapshot snapshot = new LegacySnapshotReader(accessRules, movementEvents, logger).readYamlSnapshot(yamlNetworksFile, yamlPlayerStateFile);
            String sourceHash = hashFiles(yamlNetworksFile, yamlPlayerStateFile);
            migrateSnapshot("yaml-" + System.currentTimeMillis(), sourceHash, snapshot,
                    List.of(yamlNetworksFile, yamlPlayerStateFile), null);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("Could not strictly parse YAML migration source", e);
        }
    }

    public void migrateLegacyDatabase(File legacyDatabaseFile) {
        try {
            StorageSnapshot snapshot = new LegacySnapshotReader(accessRules, movementEvents, logger).readLegacySnapshot(legacyDatabaseFile);
            migrateSnapshot("legacy-sqlite-" + System.currentTimeMillis(),
                    hashDatabaseFamily(legacyDatabaseFile), snapshot,
                    List.of(legacyDatabaseFile), legacyDatabaseFile);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("Could not strictly parse legacy SQLite migration source", e);
        }
    }

    private void migrateSnapshot(String migrationId,
                                 String sourceHash,
                                 StorageSnapshot snapshot,
                                 List<File> sourceFiles,
                                 File legacyDatabase) {
        File dataFolder = this.dataFolder;
        File lockFile = new File(dataFolder, "migration.lock");
        if (lockFile.exists()) {
            throw new StorageException("migration.lock exists; refusing to use an unverified migration");
        }
        try {
            Files.createFile(lockFile.toPath());
        } catch (IOException e) {
            throw new StorageException("Could not create migration.lock", e);
        }

        File temporaryDatabase = new File(dataFolder,
                targetDatabaseFile.getName() + ".migrating-" + UUID.randomUUID());
        SqliteNetworkStorageProvider temporaryProvider = null;
        boolean promoted = false;
        try {
            copySourceBackups(sourceFiles, legacyDatabase);
            if (!sourceHash.equals(hashMigrationSource(sourceFiles, legacyDatabase))) {
                throw new StorageException("Migration source changed while it was being prepared");
            }

            temporaryProvider = plugin == null
                    ? new SqliteNetworkStorageProvider(temporaryDatabase)
                    : new SqliteNetworkStorageProvider(plugin, temporaryDatabase);
            temporaryProvider.initialize();
            if (!temporaryProvider.setMigrationInProgress(migrationId, sourceHash)) {
                throw new StorageException("Could not mark temporary SQLite migration in progress");
            }
            if (!temporaryProvider.saveSnapshot(snapshot)) {
                throw new StorageException("Temporary SQLite snapshot write failed");
            }
            temporaryProvider.verifySnapshot(snapshot);
            if (!temporaryProvider.setMigrationMetadata(migrationId, sourceHash, snapshot)) {
                throw new StorageException("SQLite migration metadata write failed");
            }
            temporaryProvider.verifySnapshot(snapshot);
            temporaryProvider.verifyDatabase();
            temporaryProvider.shutdown();
            temporaryProvider = null;

            if (!sourceHash.equals(hashMigrationSource(sourceFiles, legacyDatabase))) {
                throw new StorageException("Migration source changed before database promotion");
            }

            promoteDatabase(temporaryDatabase, legacyDatabase);
            promoted = true;
            if (legacyDatabase == null) {
                archiveSources(sourceFiles);
            }
            logger.info("SQLite migration complete: " + snapshot.networkCount()
                    + " networks, digest=" + snapshot.digest());
        } catch (RuntimeException | IOException e) {
            logger.log(Level.SEVERE,
                    "SQLite migration failed; original data was preserved and the plugin will not enable", e);
            throw e instanceof StorageException
                    ? (StorageException) e
                    : new StorageException("SQLite migration failed", e);
        } finally {
            if (temporaryProvider != null) {
                temporaryProvider.shutdown();
            }
            if (!promoted) {
                preserveFailedTemporaryDatabase(temporaryDatabase);
                logger.severe("Migration lock retained at " + lockFile.getAbsolutePath()
                        + "; inspect the preserved temporary database before retrying.");
            } else {
                try {
                    Files.deleteIfExists(lockFile.toPath());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not remove migration.lock after success", e);
                }
            }
            if (promoted) {
                try {
                    Files.deleteIfExists(temporaryDatabase.toPath());
                    Files.deleteIfExists(Path.of(temporaryDatabase.getAbsolutePath() + "-wal"));
                    Files.deleteIfExists(Path.of(temporaryDatabase.getAbsolutePath() + "-shm"));
                } catch (IOException cleanupFailure) {
                    logger.log(Level.WARNING, "Could not clean up temporary migration database", cleanupFailure);
                }
            }
        }
    }

    private void copySourceBackup(File source) {
        File backup = new File(source.getParentFile(), source.getName()
                + ".pre-sqlite-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        try {
            Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new StorageException("Could not back up migration source " + source.getName(), e);
        }
    }

    private void copySourceBackups(List<File> sourceFiles, File legacyDatabase) {
        if (legacyDatabase != null) {
            copyDatabaseFamilyBackup(legacyDatabase);
            return;
        }
        for (File source : sourceFiles) {
            if (source.exists()) {
                copySourceBackup(source);
            }
        }
    }

    private void copyDatabaseFamilyBackup(File database) {
        File backup = new File(database.getParentFile(), database.getName()
                + ".pre-sqlite-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        try {
            copyIfExists(database, backup);
            copyIfExists(new File(database.getAbsolutePath() + "-wal"), new File(backup.getAbsolutePath() + "-wal"));
            copyIfExists(new File(database.getAbsolutePath() + "-shm"), new File(backup.getAbsolutePath() + "-shm"));
        } catch (IOException e) {
            throw new StorageException("Could not back up legacy SQLite database family", e);
        }
    }

    private void copyIfExists(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void promoteDatabase(File temporaryDatabase, File legacyDatabase) throws IOException {
        File target = targetDatabaseFile;
        File oldBackup = null;
        if (target.exists() || sidecarExists(target)) {
            String suffix = legacyDatabase == null ? "empty" : "legacy";
            oldBackup = new File(target.getParentFile(), target.getName()
                    + "." + suffix + "-" + System.currentTimeMillis() + ".db");
            moveDatabaseFamily(target, oldBackup);
        }
        try {
            moveDatabaseFamily(temporaryDatabase, target);
        } catch (IOException promotionFailure) {
            if (oldBackup != null) {
                try {
                    deleteDatabaseFamily(target);
                    moveDatabaseFamily(oldBackup, target);
                } catch (IOException restoreFailure) {
                    promotionFailure.addSuppressed(restoreFailure);
                }
            }
            throw promotionFailure;
        }
    }

    private void preserveFailedTemporaryDatabase(File temporaryDatabase) {
        if (!temporaryDatabase.exists() && !sidecarExists(temporaryDatabase)) {
            return;
        }
        File failedDatabase = new File(temporaryDatabase.getParentFile(),
                temporaryDatabase.getName() + ".failed-" + UUID.randomUUID() + ".db");
        try {
            moveDatabaseFamily(temporaryDatabase, failedDatabase);
        } catch (IOException e) {
            logger.log(Level.SEVERE,
                    "Could not rename failed temporary database; it remains at " + temporaryDatabase, e);
        }
    }

    private boolean sidecarExists(File database) {
        return new File(database.getAbsolutePath() + "-wal").exists()
                || new File(database.getAbsolutePath() + "-shm").exists();
    }

    private void moveDatabaseFamily(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        moveIfExists(new File(source.getAbsolutePath() + "-wal"),
                new File(destination.getAbsolutePath() + "-wal"));
        moveIfExists(new File(source.getAbsolutePath() + "-shm"),
                new File(destination.getAbsolutePath() + "-shm"));
    }

    private void moveIfExists(File source, File destination) throws IOException {
        if (source.exists()) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void deleteDatabaseFamily(File database) throws IOException {
        Files.deleteIfExists(database.toPath());
        Files.deleteIfExists(Path.of(database.getAbsolutePath() + "-wal"));
        Files.deleteIfExists(Path.of(database.getAbsolutePath() + "-shm"));
    }

    private void archiveSources(List<File> sourceFiles) {
        for (File source : sourceFiles) {
            if (!source.exists()) {
                continue;
            }
            File archive = new File(source.getParentFile(), source.getName()
                    + ".migrated-" + System.currentTimeMillis());
            try {
                Files.move(source.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.log(Level.WARNING,
                        "Could not archive migration source " + source.getName()
                                + "; it was retained and will not be re-imported", e);
            }
        }
    }

    private String hashFiles(File... files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (File file : files) {
                if (!file.exists()) {
                    continue;
                }
                digest.update(file.getName().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file.toPath()));
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new StorageException("Could not hash migration source", e);
        }
    }

    private String hashMigrationSource(List<File> sourceFiles, File legacyDatabase) {
        return legacyDatabase == null ? hashFiles(sourceFiles.toArray(new File[0])) : hashDatabaseFamily(legacyDatabase);
    }

    private String hashDatabaseFamily(File database) {
        return hashFiles(database, new File(database.getAbsolutePath() + "-wal"),
                new File(database.getAbsolutePath() + "-shm"));
    }

}
