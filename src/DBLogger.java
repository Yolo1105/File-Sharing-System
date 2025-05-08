import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DBLogger {
    private static volatile boolean initialized = false;
    private static boolean initializing = false;

    /**
     * Initializes the database and ensures the schema is correct
     */
    private static synchronized void initializeDatabase() {
        if (initialized || initializing) return;

        initializing = true;
        System.out.println("[INIT] Initializing DBLogger...");

        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }

            boolean tableExists;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "logs", null)) {
                tableExists = rs.next();
            }

            if (!tableExists) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("""
                        CREATE TABLE logs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            client TEXT NOT NULL,
                            action TEXT NOT NULL,
                            filename TEXT NOT NULL,
                            timestamp TEXT NOT NULL
                        )
                    """);
                    System.out.println("[INIT] Created logs table with correct schema");
                }
            }

            initialized = true;
            System.out.println("[INIT] Database logger initialized successfully");

            // Now it's safe to use the Logger
            try {
                Logger.getInstance().log(Logger.Level.INFO, "DBLogger", "Database logger initialized successfully");
            } catch (Exception e) {
                // If logger still has issues, just use console logging
                System.out.println("[INFO] Database logger initialized successfully");
            }

        } catch (SQLException e) {
            System.err.println("[FATAL] Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        } finally {
            pool.releaseConnection(conn);
            initializing = false;
        }
    }

    /**
     * Logs a file operation to the database
     * @param client Client name
     * @param action Action (UPLOAD, DOWNLOAD, etc.)
     * @param filename Filename
     */
    public static void log(String client, String action, String filename) {
        // Initialize if needed
        if (!initialized) {
            initializeDatabase();
        }

        if (client == null || client.trim().isEmpty()) {
            client = "Unknown";
        }

        // Clean up client name by removing suffixes like _upload or _download
        if (client.contains("_")) {
            client = client.substring(0, client.indexOf("_"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO logs (client, action, filename, timestamp) VALUES (?, ?, ?, ?)")) {
                pstmt.setString(1, client);
                pstmt.setString(2, action.toUpperCase());
                pstmt.setString(3, filename);
                pstmt.setString(4, timestamp);
                pstmt.executeUpdate();

                // Log to regular logger if available
                try {
                    Logger.getInstance().log(Logger.Level.INFO, "DBLogger", "Logged: " + client + " " + action + " " + filename);
                } catch (Exception e) {
                    // Fall back to console
                    System.out.println("[INFO] Logged: " + client + " " + action + " " + filename);
                }
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to log action: " + e.getMessage());
            try {
                Logger.getInstance().log(Logger.Level.ERROR, "DBLogger", "Failed to log action: " + e.getMessage(), e);
            } catch (Exception ex) {
                // Logger issue, just use console
                System.err.println("[ERROR] Failed to log action: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Gets recent logs from the database
     * @param limit Maximum number of logs to retrieve
     * @return String representation of the logs
     */
    public static String getRecentLogs(int limit) {
        if (!initialized) {
            initializeDatabase();
        }

        StringBuilder result = new StringBuilder();
        result.append("=== File Logs (Last ").append(limit).append(" Actions) ===\n");

        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT * FROM logs ORDER BY timestamp DESC LIMIT ?")) {

                pstmt.setInt(1, limit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean hasLogs = false;

                    while (rs.next()) {
                        hasLogs = true;

                        // Get the timestamp from database
                        String timestamp = rs.getString("timestamp");

                        // Parse timestamp and reformat to HH:mm
                        try {
                            LocalDateTime dateTime = LocalDateTime.parse(timestamp,
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                            // Format just the hour:minute
                            String timeOnly = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));

                            // Get the client name without any suffix
                            String client = rs.getString("client");
                            if (client.contains("_")) {
                                client = client.substring(0, client.indexOf("_"));
                            }

                            // Format the log entry as requested
                            result.append("[").append(timeOnly).append("] ")
                                    .append(client).append(" ")
                                    .append(rs.getString("action")).append(": ")
                                    .append(rs.getString("filename")).append("\n");
                        } catch (Exception e) {
                            // If any error in formatting, fall back to original format
                            result.append("[")
                                    .append(timestamp).append("] ")
                                    .append(rs.getString("client")).append(" ")
                                    .append(rs.getString("action")).append(": ")
                                    .append(rs.getString("filename")).append("\n");
                        }
                    }

                    if (!hasLogs) {
                        result.append("No logs found.\n");
                    }
                }
            }
        } catch (SQLException e) {
            result.append("ERROR: Failed to retrieve logs: ").append(e.getMessage()).append("\n");
            try {
                Logger.getInstance().log(Logger.Level.ERROR, "DBLogger", "Failed to retrieve logs: " + e.getMessage(), e);
            } catch (Exception ex) {
                // Logger issue, just use console
                System.err.println("[ERROR] Failed to retrieve logs: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            pool.releaseConnection(conn);
        }

        return result.toString();
    }

    /**
     * Resets the database by dropping and recreating the logs table
     */
    public static void resetDatabase() {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE IF EXISTS logs");
                stmt.executeUpdate("""
                    CREATE TABLE logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        client TEXT NOT NULL,
                        action TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                """);

                initialized = true;
                Logger.getInstance().log(Logger.Level.INFO, "DBLogger", "Database reset successfully");
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to reset database: " + e.getMessage());
            try {
                Logger.getInstance().log(Logger.Level.ERROR, "DBLogger", "Failed to reset database: " + e.getMessage(), e);
            } catch (Exception ex) {
                // Logger issue, just use console
                System.err.println("[ERROR] Failed to reset database: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }
}