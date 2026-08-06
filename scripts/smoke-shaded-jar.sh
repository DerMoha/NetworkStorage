#!/usr/bin/env bash
set -euo pipefail

jar_path="${1:-target/NetworkStorage-1.5.0.jar}"
if [[ ! -f "${jar_path}" ]]; then
  echo "Missing shaded jar: ${jar_path}" >&2
  exit 1
fi

jshell --class-path "${jar_path}" <<'EOF'
import java.sql.*;
Class.forName("org.sqlite.JDBC");
try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
     var statement = connection.createStatement();
     var result = statement.executeQuery("select sqlite_version()")) {
    if (!result.next()) throw new IllegalStateException("SQLite returned no version");
    System.out.println("shaded sqlite=" + result.getString(1));
}
EOF
