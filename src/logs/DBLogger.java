package logs;

import constants.Constants;
import database.Database;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
public class DBLogger {
    private static final Logger logger = Logger.getInstance();
    private static volatile boolean initialized = false;
    private static boolean initializing = false;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static synchronized void initialize() {
        if (initialized || initializing) return;
        initializing = true;

        try {
            boolean tableExists = Database.queryConnection(conn -> {
                try {
                    return Database.tableExists(conn, "logs");
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "DBLogger", "Failed to checking if logs table exists", e);
                    return false;
                }
            }, false, "DBLogger");

            if (!tableExists) {
                logger.log(Logger.Level.WARNING, "DBLogger",
                        "Logs table doesn't exist, tables may not be initialized properly");
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
        if (!initialized) {
            initialize();
        }

        client = normalizeClientName(client);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

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

                        logger.log(Logger.Level.INFO, "DBLogger",
                                String.format("Logged: %s %s %s", finalClient, finalAction, finalFilename));
                    }
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "DBLogger", "Failed to executing SQL: " + e.getMessage(), e);
                    throw new RuntimeException(e); 
                }
            }, "DBLogger");
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", Constants.ErrorMessages.ERR_LOG_FAILED, e);
        }
    }

    private static String normalizeClientName(String client) {
        if (client == null || client.trim().isEmpty()) {
            return "Unknown";
        }

        if (client.contains("_")) {
            return client.substring(0, client.indexOf("_"));
        }

        return client;
    }

    public static String getRecentLogs(int limit) {
        if (!initialized) {
            initialize();
        }

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
                                logEntry(rs, result);
                            }

                            if (!hasLogs) {
                                result.append("No logs found.\n");
                            }
                        }
                    }
                    logger.log(Logger.Level.INFO, "DBLogger", "Retrieved " + finalLimit + " log entries");
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "DBLogger", "Failed to executing SQL: " + e.getMessage(), e);
                    throw new RuntimeException(e); 
                }
            }, "DBLogger");
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", Constants.ErrorMessages.ERR_LOGS_FAILED, e);
            result.append("ERROR: ").append(Constants.ErrorMessages.ERR_LOGS_FAILED).append(": ").append(e.getMessage()).append("\n");
        }
        return result.toString();
    }

    private static void logEntry(ResultSet rs, StringBuilder result) {
        try {
            String timestamp = rs.getString("timestamp");
            String client = rs.getString("client");
            String action = rs.getString("action");
            String filename = rs.getString("filename");

            try {
                LocalDateTime dateTime = LocalDateTime.parse(timestamp, TIMESTAMP_FORMAT);
                String formattedTime = dateTime.format(DISPLAY_FORMAT);

                client = normalizeClientName(client);
                result.append(String.format("[%s] %s %s: %s\n",
                        formattedTime, client, action, filename));

            } catch (Exception e) {
                result.append(String.format("[%s] %s %s: %s\n",
                        timestamp, client, action, filename));
                logger.log(Logger.Level.WARNING, "DBLogger",
                        "Failed to formatting log entry: " + e.getMessage());
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", "Failed to reading log entry: " + e.getMessage(), e);
            result.append("Failed to reading log entry\n");
        }
    }
}