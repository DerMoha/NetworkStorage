package com.dermoha.networkstorage.storage;

import com.dermoha.networkstorage.NetworkStoragePlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class RollingBackupManager {

    private static final int MAX_BACKUPS = 3;
    private final NetworkStoragePlugin plugin;
    private final File backupDirectory;

    public RollingBackupManager(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.backupDirectory = new File(plugin.getDataFolder(), "backups");
    }

    public void initialize() {
        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            plugin.getLogger().warning("Could not create backup directory at " + backupDirectory.getAbsolutePath());
        }
    }

    public void rotateBackups(String baseFileName) {
        if (!backupDirectory.exists()) {
            return;
        }
        List<File> existing = new ArrayList<>();
        for (File file : backupDirectory.listFiles((dir, name) -> name.startsWith(baseFileName + ".") && name.endsWith(".bak"))) {
            if (file != null) {
                existing.add(file);
            }
        }
        Collections.sort(existing);
        while (existing.size() >= MAX_BACKUPS) {
            File oldest = existing.remove(0);
            if (!oldest.delete()) {
                plugin.getLogger().warning("Could not delete old backup " + oldest.getName());
            }
        }
    }

    public void createBackup(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0) {
            return;
        }
        if (!backupDirectory.exists()) {
            initialize();
        }
        String baseFileName = sourceFile.getName();
        rotateBackups(baseFileName);
        long timestamp = System.currentTimeMillis();
        File target = new File(backupDirectory, baseFileName + "." + timestamp + ".bak");
        try {
            Files.copy(sourceFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not create backup of " + baseFileName, e);
        }
    }
}
