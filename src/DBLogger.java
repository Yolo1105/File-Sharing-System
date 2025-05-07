import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DBLogger {
    private static final Logger logger = Logger.getInstance();
    private static volatile boolean initialized = false;

    static {
        initializeDatabase();
    }

    /**
     * Initializes the database and ensures the schema is correct
     */
    private static synchronized void initializeDatabase() {
        if (initialized) return;

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
                    logger.log(Logger.Level.INFO, "DBLogger", "Created logs table with correct schema");
                }
            }

            initialized = true;
            logger.log(Logger.Level.INFO, "DBLogger", "Database logger initialized successfully");

        } catch (SQLException e) {
            logger.log(Logger.Level.FATAL, "DBLogger", "Failed to initialize database: " + e.getMessage(), e);
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
        if (!initialized) {
            initializeDatabase();
        }

        if (client == null || client.trim().isEmpty()) {
            client = "Unknown";
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

                logger.log(Logger.Level.INFO, "DBLogger", "Logged: " + client + " " + action + " " + filename);
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", "Failed to log action: " + e.getMessage(), e);
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
                        result.append("[")
                                .append(rs.getString("timestamp")).append("] ")
                                .append(rs.getString("client")).append(" ")
                                .append(rs.getString("action")).append(": ")
                                .append(rs.getString("filename")).append("\n");
                    }

                    if (!hasLogs) {
                        result.append("No logs found.\n");
                    }
                }
            }
        } catch (SQLException e) {
            result.append("ERROR: Failed to retrieve logs: ").append(e.getMessage()).append("\n");
            logger.log(Logger.Level.ERROR, "DBLogger", "Failed to retrieve logs: " + e.getMessage(), e);
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
                logger.log(Logger.Level.INFO, "DBLogger", "Database reset successfully");
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", "Failed to reset database: " + e.getMessage(), e);
        } finally {
            pool.releaseConnection(conn);
        }
    }
}
