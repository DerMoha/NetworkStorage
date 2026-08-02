package com.dermoha.networkstorage.storage.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteConcurrencyTest {

    private Path tempDir;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        tempDir = Files.createTempDirectory("ns-sqlite-");
        connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"));

        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
            s.execute("""
                CREATE TABLE counters (
                  name TEXT PRIMARY KEY,
                  value INTEGER NOT NULL DEFAULT 0
                )
            """);
            s.execute("INSERT INTO counters(name, value) VALUES ('shared', 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (tempDir != null) {
            deleteRecursively(tempDir.toFile());
        }
    }

    @Test
    void pragmasTakeEffect() throws Exception {
        try (Statement s = connection.createStatement()) {
            Object journal = readPragma(s, "journal_mode");
            assertEquals("wal", String.valueOf(journal).toLowerCase());
            Object foreignKeys = readPragma(s, "foreign_keys");
            assertEquals("1", String.valueOf(foreignKeys));
        }
    }

    @Test
    void concurrentWritersDoNotSurfaceBusy() throws Exception {
        int threadCount = 8;
        int writesPerThread = 50;

        java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();
        List<String> failureMessages = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        writeLock.lock();
                        try (Statement s = connection.createStatement()) {
                            s.execute("BEGIN IMMEDIATE");
                            s.executeUpdate("UPDATE counters SET value = value + 1 WHERE name = 'shared'");
                            s.execute("COMMIT");
                        } finally {
                            writeLock.unlock();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    synchronized (failureMessages) {
                        failureMessages.add("Thread " + threadId + ": " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers did not complete in time");
        pool.shutdown();

        assertEquals(0, failures.get(), "Unexpected failures: " + failureMessages);

        try (Statement s = connection.createStatement();
             var rs = s.executeQuery("SELECT value FROM counters WHERE name = 'shared'")) {
            assertTrue(rs.next());
            assertEquals((long) threadCount * writesPerThread, rs.getLong(1));
        }
    }

    @Test
    void foreignKeyCascadeDeletesChildRows() throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE parent (id INTEGER PRIMARY KEY)
            """);
            s.execute("""
                CREATE TABLE child (
                  parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE
                )
            """);
            s.execute("INSERT INTO parent VALUES (1)");
            s.execute("INSERT INTO child VALUES (1)");
            s.execute("INSERT INTO child VALUES (1)");
            s.execute("DELETE FROM parent WHERE id = 1");
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM child")) {
                assertTrue(rs.next());
                assertEquals(0L, rs.getLong(1));
            }
        }
    }

    private Object readPragma(Statement s, String key) throws SQLException {
        try (var rs = s.executeQuery("PRAGMA " + key)) {
            assertNotNull(rs);
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private void deleteRecursively(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        file.delete();
    }
}
