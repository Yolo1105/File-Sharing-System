import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DBLogger {
    private static final Logger logger = Logger.getInstance();

    static {
        try {
            // Initialize the database
            try (Connection conn = ConnectionPool.getInstance().getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        client TEXT NOT NULL,
                        action TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                """);

                ConnectionPool.getInstance().releaseConnection(conn);
            }

            logger.log(Logger.Level.INFO, "DBLogger", "Database logger initialized successfully");

        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "DBLogger", "Failed to initialize database", e);
        }
    }

    public static void log(String client, String action, String filename) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Connection connection = null;
        try {
            connection = ConnectionPool.getInstance().getConnection();
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT INTO logs (client, action, filename, timestamp) VALUES (?, ?, ?, ?)")) {
                pstmt.setString(1, client);
                pstmt.setString(2, action.toUpperCase());
                pstmt.setString(3, filename);
                pstmt.setString(4, timestamp);
                pstmt.executeUpdate();
                logger.log(Logger.Level.INFO, "DBLogger", "Logged: " + client + " " + action + " " + filename);
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "DBLogger", "Failed to log action", e);
        } finally {
            if (connection != null) {
                try {
                    ConnectionPool.getInstance().releaseConnection(connection);
                } catch (Exception e) {
                    logger.log(Logger.Level.ERROR, "DBLogger", "Error releasing connection", e);
                }
            }
        }
    }
}