import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.io.InputStream;

public class DBLogger {
    private static Connection connection;

    static {
        try {
            Properties props = new Properties();
            try (InputStream in = DBLogger.class.getClassLoader().getResourceAsStream("db_config.properties")) {
                props.load(in);
            }

            String dbUrl = props.getProperty("jdbc.url");
            connection = DriverManager.getConnection(dbUrl);

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        client TEXT NOT NULL,
                        action TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                """);
            }

            System.out.println("[DB] Logger initialized.");

        } catch (Exception e) {
            System.err.println("[DB] Failed to initialize DBLogger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void log(String client, String action, String filename) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (PreparedStatement pstmt = connection.prepareStatement(
                "INSERT INTO logs (client, action, filename, timestamp) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, client);
            pstmt.setString(2, action.toUpperCase());
            pstmt.setString(3, filename);
            pstmt.setString(4, timestamp);
            pstmt.executeUpdate();
            System.out.println("[DB] Logged: " + client + " " + action + " " + filename + " at " + timestamp);
        } catch (SQLException e) {
            System.err.println("[DB] Failed to log action: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
