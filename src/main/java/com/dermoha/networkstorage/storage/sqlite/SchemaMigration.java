package com.dermoha.networkstorage.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

public interface SchemaMigration {

    int targetVersion();

    void migrate(Connection connection) throws SQLException;
}
