import java.io.FileInputStream;
import java.sql.*;
import java.util.Properties;

public class DBLogger {
    private static Connection connection;

    public static void init() {
        try {
            Properties props = new Properties();
            props.load(new FileInputStream("db_config.properties"));
            connection = DriverManager.getConnection(
                    props.getProperty("url"),
                    props.getProperty("user"),
                    props.getProperty("password"));
            Statement stmt = connection.createStatement();
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT, filename TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void log(String action, String filename) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO logs (action, filename) VALUES (?, ?)")) {
            ps.setString(1, action);
            ps.setString(2, filename);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
