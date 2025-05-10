package logs;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import database.Database;
import constants.Constants;

public class DBLogger {
    private static final Logger logger = Logger.getInstance();
    private static volatile boolean initialized = false;
    private static boolean initializing = false;

    // Date format patterns
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static synchronized void initialize() {
        if (initialized || initializing) return;

        initializing = true;
        logger.log(Logger.Level.INFO, "DBLogger", "Initializing database logger - tables should be already created");

        try {
            // Just verify the table exists as a sanity check
            boolean tableExists = Database.queryWithConnection(conn -> {
                try {
                    // Use the tableExists helper method from ConnectionManager instead
                    return Database.tableExists(conn, "logs");
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "DBLogger", "Error checking if logs table exists", e);
                    return false;
                }
            }, false, "DBLogger");

            if (!tableExists) {
                logger.log(Logger.Level.WARNING, "DBLogger",
                        "Logs table doesn't exist, tables may not be properly initialized");
                initializing = false;
                return;
            }

            initialized = true;
            logger.log(Logger.Level.INFO, "DBLogger", "Database logger initialized successfully");
        } catch (Exception e) {
            logger.log(Logger.Level.ERROR, "DBLogger", Constants.ErrorMessages.ERR_INIT_FAILED, e);
        } finally {
            initializing = false;
        }
    }

    public static void log(String client, String action, String filename) {
        // Initialize if needed
        if (!initialized) {
            initialize();
        }

        // Normalize client name
        client = normalizeClientName(client);

        // Generate current timestamp
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // Final variables for lambda capture
        final String finalClient = client;
        final String finalAction = action;
        final String finalFilename = filename;
        final String finalTimestamp = timestamp;

        try {
            Database.executeWithConnection(conn -> {
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO logs (client, action, filename, timestamp) VALUES (?, ?, ?, ?)")) {
                        pstmt.setString(1, finalClient);
                        pstmt.setString(2, finalAction.toUpperCase());
                        pstmt.setString(3, finalFilename);
                        pstmt.setString(4, finalTimestamp);
                        pstmt.executeUpdate();

                        // Log successful operation
                        logger.log(Logger.Level.INFO, "DBLogger",
                                String.format("Logged: %s %s %s", finalClient, finalAction, finalFilename));
                    }
                } catch (SQLException e) {
                    // Handle SQL Exception inside the lambda
                    logger.log(Logger.Level.ERROR, "DBLogger", "Error executing SQL: " + e.getMessage(), e);
                    throw new RuntimeException(e); // Re-throw to be caught by executeWithConnection
                }
            }, "DBLogger");
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", Constants.ErrorMessages.ERR_LOG_FAILED, e);
        }
    }

    private static String normalizeClientName(String client) {
        // Normalize client name
        if (client == null || client.trim().isEmpty()) {
            return "Unknown";
        }

        // Clean up client name by removing suffixes like _upload or _download
        if (client.contains("_")) {
            return client.substring(0, client.indexOf("_"));
        }

        return client;
    }

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

            Database.executeWithConnection(conn -> {
                try {
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT * FROM logs ORDER BY timestamp DESC LIMIT ?")) {

                        pstmt.setInt(1, finalLimit);

                        try (ResultSet rs = pstmt.executeQuery()) {
                            boolean hasLogs = false;

                            while (rs.next()) {
                                hasLogs = true;
                                formatLogEntry(rs, result);
                            }

                            if (!hasLogs) {
                                result.append("No logs found.\n");
                            }
                        }
                    }

                    logger.log(Logger.Level.INFO, "DBLogger", "Retrieved " + finalLimit + " log entries");
                } catch (SQLException e) {
                    // Handle SQL Exception inside the lambda
                    logger.log(Logger.Level.ERROR, "DBLogger", "Error executing SQL: " + e.getMessage(), e);
                    throw new RuntimeException(e); // Re-throw to be caught by executeWithConnection
                }
            }, "DBLogger");
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", Constants.ErrorMessages.ERR_LOGS_FAILED, e);
            result.append("ERROR: ").append(Constants.ErrorMessages.ERR_LOGS_FAILED).append(": ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    private static void formatLogEntry(ResultSet rs, StringBuilder result) {
        try {
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
                client = normalizeClientName(client);

                // Format the log entry with year and date included
                result.append(String.format("[%s] %s %s: %s\n",
                        formattedTime, client, action, filename));

            } catch (Exception e) {
                // If any error in formatting, fall back to original format
                result.append(String.format("[%s] %s %s: %s\n",
                        timestamp, client, action, filename));

                logger.log(Logger.Level.WARNING, "DBLogger",
                        "Error formatting log entry: " + e.getMessage());
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", "Error reading log entry: " + e.getMessage(), e);
            result.append("Error reading log entry\n");
        }
    }
}