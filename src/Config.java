import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "config.properties";

    static {
        boolean loadedConfig = false;

        // First try to load from the file system
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (InputStream input = new java.io.FileInputStream(configFile)) {
                properties.load(input);
                System.out.println("[CONFIG] Configuration loaded from file: " + configFile.getAbsolutePath());
                loadedConfig = true;
            } catch (IOException e) {
                System.err.println("[CONFIG] Error loading configuration from file: " + e.getMessage());
            }
        }

        // If not loaded from file system, try classpath
        if (!loadedConfig) {
            try (InputStream input = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (input == null) {
                    System.err.println("[CONFIG] Could not find " + CONFIG_FILE + ", using defaults");
                    setDefaults();
                } else {
                    properties.load(input);
                    System.out.println("[CONFIG] Configuration loaded from classpath");
                    loadedConfig = true;
                }
            } catch (IOException e) {
                System.err.println("[CONFIG] Error loading configuration: " + e.getMessage());
                setDefaults();
            }
        }

        // Log the loaded configuration
        System.out.println("[CONFIG] Server port: " + getServerPort());
        System.out.println("[CONFIG] Max threads: " + getMaxThreads());
        System.out.println("[CONFIG] Files directory: " + getFilesDirectory());
        System.out.println("[CONFIG] DB URL: " + getDbUrl());
    }

    private static void setDefaults() {
        properties.setProperty("server.port", "12345");
        properties.setProperty("server.max_threads", "50");  // Increased from 10 to 50
        properties.setProperty("files.directory", "server_files/");
        properties.setProperty("db.url", "jdbc:sqlite:file_logs.db");
        properties.setProperty("server.host", "localhost");
        System.out.println("[CONFIG] Default configuration set");
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

    /**
     * Creates a config.properties file with default values if it doesn't exist
     */
    public static void createDefaultConfigFile() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            try {
                Properties defaultProps = new Properties();
                defaultProps.setProperty("server.port", "12345");
                defaultProps.setProperty("server.max_threads", "50");  // Increased from 10 to 50
                defaultProps.setProperty("files.directory", "server_files/");
                defaultProps.setProperty("db.url", "jdbc:sqlite:file_logs.db");
                defaultProps.setProperty("server.host", "localhost");

                try (java.io.FileOutputStream out = new java.io.FileOutputStream(configFile)) {
                    defaultProps.store(out, "Default configuration for File Sharing System");
                }

                System.out.println("[CONFIG] Created default configuration file: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to create default configuration file: " + e.getMessage());
            }
        }
    }
}