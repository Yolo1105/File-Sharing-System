import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                System.err.println("[CONFIG] Could not find config.properties, using defaults");
                setDefaults();
            } else {
                properties.load(input);
                System.out.println("[CONFIG] Configuration loaded successfully");
            }
        } catch (IOException e) {
            System.err.println("[CONFIG] Error loading configuration: " + e.getMessage());
            setDefaults();
        }
    }

    private static void setDefaults() {
        properties.setProperty("server.port", "12345");
        properties.setProperty("server.max_threads", "10");
        properties.setProperty("files.directory", "server_files/");
        properties.setProperty("db.url", "jdbc:sqlite:file_logs.db");
        properties.setProperty("server.host", "localhost");
    }

    public static int getServerPort() {
        return Integer.parseInt(properties.getProperty("server.port"));
    }

    public static int getMaxThreads() {
        return Integer.parseInt(properties.getProperty("server.max_threads"));
    }

    public static String getFilesDirectory() {
        return properties.getProperty("files.directory");
    }

    public static String getDbUrl() {
        return properties.getProperty("db.url");
    }

    public static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
}