package logs; // Add package declaration

import logs.Logger;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import database.ConnectionManager;

public class DBLogger {
    private static final Logger logger = Logger.getInstance();
    private static volatile boolean initialized = false;
    private static boolean initializing = false;

    // Date format patterns
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Change this to include year and date in the display format
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Error messages
    private static final String ERR_INIT_FAILED = "Failed to initialize database logger";
    private static final String ERR_LOG_FAILED = "Failed to log action";
    private static final String ERR_LOGS_FAILED = "Failed to retrieve logs";
    private static final String ERR_RESET_FAILED = "Failed to reset database";

    /**
     * Database operation functional interface for reducing duplication
     */
    @FunctionalInterface
    private interface DatabaseOperation {
        void execute(Connection connection) throws SQLException;
    }

    /**
     * Initializes the database and ensures the schema is correct
     */
    private static synchronized void initialize() {
        if (initialized || initializing) return;

        initializing = true;
        logger.log(Logger.Level.INFO, "DBLogger", "Initializing database logger - tables should be already created");

        // No need to create tables here anymore - ConnectionManager.initializeDatabaseSchema handles it
        try {
            // Just verify the table exists as a sanity check
            withConnection(conn -> {
                // Verify logs table exists
                try (ResultSet rs = conn.getMetaData().getTables(null, null, "logs", null)) {
                    if (!rs.next()) {
                        logger.log(Logger.Level.WARNING, "DBLogger",
                                "Logs table doesn't exist, tables may not be properly initialized");
                        return;
                    }
                }

                initialized = true;
                logger.log(Logger.Level.INFO, "DBLogger", "Database logger initialized successfully");
            });
        } catch (Exception e) {
            logger.log(Logger.Level.ERROR, "DBLogger", ERR_INIT_FAILED, e);
        } finally {
            initializing = false;
        }
    }

    /**
     * Helper method to standardize connection handling
     */
    private static void withConnection(DatabaseOperation operation) throws SQLException {
        ConnectionManager pool = ConnectionManager.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            operation.execute(conn);
        } finally {
            pool.releaseConnection(conn);
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
            initialize();
        }

        // Normalize client name
        if (client == null || client.trim().isEmpty()) {
            client = "Unknown";
        }

        // Clean up client name by removing suffixes like _upload or _download
        if (client.contains("_")) {
            client = client.substring(0, client.indexOf("_"));
        }

        // Generate current timestamp
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // Final variables for lambda capture
        final String finalClient = client;
        final String finalAction = action;
        final String finalFilename = filename;
        final String finalTimestamp = timestamp;

        try {
            withConnection(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO logs (client, action, filename, timestamp) VALUES (?, ?, ?, ?)")) {
                    pstmt.setString(1, finalClient);
                    pstmt.setString(2, finalAction.toUpperCase());
                    pstmt.setString(3, finalFilename);
                    pstmt.setString(4, finalTimestamp);
                    pstmt.executeUpdate();

                    // Log successful operation
                    logger.log(Logger.Level.INFO, "DBLogger",
                            "Logged: " + finalClient + " " + finalAction + " " + finalFilename);
                }
            });
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", ERR_LOG_FAILED, e);
        }
    }

    /**
     * Gets recent logs from the database
     * @param limit Maximum number of logs to retrieve
     * @return String representation of the logs
     */
    public static String getRecentLogs(int limit) {
        // Initialize if needed
        if (!initialized) {
            initialize();
        }

        // Enforce reasonable limits
        int safeLimit = Math.max(1, Math.min(limit, 100));

        StringBuilder result = new StringBuilder();
        result.append("=== File Logs (Last ").append(safeLimit).append(" Actions) ===\n");

        try {
            final int finalLimit = safeLimit;
            final StringBuilder finalResult = result;

            withConnection(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT * FROM logs ORDER BY timestamp DESC LIMIT ?")) {

                    pstmt.setInt(1, finalLimit);

                    try (ResultSet rs = pstmt.executeQuery()) {
                        boolean hasLogs = false;

                        while (rs.next()) {
                            hasLogs = true;

                            // Get the values from database
                            String timestamp = rs.getString("timestamp");
                            String client = rs.getString("client");
                            String action = rs.getString("action");
                            String filename = rs.getString("filename");

                            // Parse timestamp and reformat to include year and date
                            try {
                                LocalDateTime dateTime = LocalDateTime.parse(timestamp, TIMESTAMP_FORMAT);
                                String formattedTime = dateTime.format(DISPLAY_FORMAT);

                                // Clean up client name if needed
                                if (client.contains("_")) {
                                    client = client.substring(0, client.indexOf("_"));
                                }

                                // Format the log entry with year and date included
                                finalResult.append("[").append(formattedTime).append("] ")
                                        .append(client).append(" ")
                                        .append(action).append(": ")
                                        .append(filename).append("\n");

                            } catch (Exception e) {
                                // If any error in formatting, fall back to original format
                                finalResult.append("[").append(timestamp).append("] ")
                                        .append(client).append(" ")
                                        .append(action).append(": ")
                                        .append(filename).append("\n");

                                logger.log(Logger.Level.WARNING, "DBLogger",
                                        "Error formatting log entry: " + e.getMessage());
                            }
                        }

                        if (!hasLogs) {
                            finalResult.append("No logs found.\n");
                        }
                    }
                }

                logger.log(Logger.Level.INFO, "DBLogger", "Retrieved " + finalLimit + " log entries");
            });
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", ERR_LOGS_FAILED, e);
            result.append("ERROR: ").append(ERR_LOGS_FAILED).append(": ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    /**
     * Resets the database by dropping and recreating the logs table
     * @deprecated This method is not currently used but kept for potential future maintenance
     */
    @Deprecated
    public static void resetDatabase() {
        logger.log(Logger.Level.WARNING, "DBLogger", "Resetting logs database table");

        try {
            withConnection(conn -> {
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
                    logger.log(Logger.Level.INFO, "DBLogger", "Database reset successfully");
                }
            });
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", ERR_RESET_FAILED, e);
        }
    }
}