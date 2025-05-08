import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class Config {
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "config.properties";
    // Cache for frequently accessed properties
    private static final ConcurrentHashMap<String, Object> propertyCache = new ConcurrentHashMap<>();

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

        // Set more optimized defaults if needed
        optimizeProperties();

        // Log the loaded configuration
        System.out.println("[CONFIG] Server port: " + getServerPort());
        System.out.println("[CONFIG] Max threads: " + getMaxThreads());
        System.out.println("[CONFIG] Files directory: " + getFilesDirectory());
        System.out.println("[CONFIG] DB URL: " + getDbUrl());
        System.out.println("[CONFIG] Debug mode: " + getProperty("debug.mode", "false"));
        System.out.println("[CONFIG] Buffer size: " + getProperty("buffer.size", "32768"));
    }

    private static void setDefaults() {
        properties.setProperty("server.port", "12345");
        properties.setProperty("server.max_threads", "50");
        properties.setProperty("files.directory", "server_files/");
        properties.setProperty("db.url", "jdbc:sqlite:file_logs.db");
        properties.setProperty("server.host", "localhost");
        properties.setProperty("debug.mode", "false");
        properties.setProperty("buffer.size", "32768");
        properties.setProperty("socket.timeout", "120000");
        System.out.println("[CONFIG] Default configuration set");
    }

    /**
     * Optimize property values for better performance
     */
    private static void optimizeProperties() {
        // Ensure buffer sizes are power of 2 and not too small
        int bufferSize = getIntProperty("buffer.size", 8192);
        if (bufferSize < 8192 || (bufferSize & (bufferSize - 1)) != 0) {
            // Round up to nearest power of 2, minimum 8k
            int optimizedSize = 8192;
            while (optimizedSize < bufferSize) {
                optimizedSize *= 2;
            }
            properties.setProperty("buffer.size", String.valueOf(optimizedSize));
            System.out.println("[CONFIG] Optimized buffer.size from " + bufferSize + " to " + optimizedSize);
        }

        // Ensure timeout isn't too short
        int socketTimeout = getIntProperty("socket.timeout", 30000);
        if (socketTimeout < 30000) {
            properties.setProperty("socket.timeout", "30000");
            System.out.println("[CONFIG] Increased socket.timeout to minimum 30000ms");
        }
    }

    /**
     * Gets an integer property with default value
     */
    public static int getIntProperty(String key, int defaultValue) {
        // Check cache first
        if (propertyCache.containsKey(key)) {
            Object value = propertyCache.get(key);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        }

        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }

        try {
            int result = Integer.parseInt(value);
            propertyCache.put(key, result);
            return result;
        } catch (NumberFormatException e) {
            System.err.println("[CONFIG] Invalid integer property: " + key + " = " + value);
            return defaultValue;
        }
    }

    public static int getServerPort() {
        return getIntProperty("server.port", 12345);
    }

    public static int getMaxThreads() {
        return getIntProperty("server.max_threads", 50);
    }

    public static int getBufferSize() {
        return getIntProperty("buffer.size", 32768);
    }

    public static int getSocketTimeout() {
        return getIntProperty("socket.timeout", 120000);
    }

    public static String getFilesDirectory() {
        // Check cache first
        if (propertyCache.containsKey("files.directory")) {
            return (String) propertyCache.get("files.directory");
        }

        String dir = properties.getProperty("files.directory", "server_files/");
        // Ensure directory ends with file separator
        if (!dir.endsWith("/") && !dir.endsWith("\\")) {
            dir = dir + File.separator;
        }

        propertyCache.put("files.directory", dir);
        return dir;
    }

    public static String getDbUrl() {
        // Check cache first
        if (propertyCache.containsKey("db.url")) {
            return (String) propertyCache.get("db.url");
        }

        String url = properties.getProperty("db.url", "jdbc:sqlite:file_logs.db");
        propertyCache.put("db.url", url);
        return url;
    }

    public static String getProperty(String key, String defaultValue) {
        // Check cache first
        if (propertyCache.containsKey(key)) {
            Object value = propertyCache.get(key);
            if (value instanceof String) {
                return (String) value;
            }
        }

        String value = properties.getProperty(key, defaultValue);
        propertyCache.put(key, value);
        return value;
    }

    /**
     * Creates a config.properties file with optimized default values if it doesn't exist
     */
    public static void createDefaultConfigFile() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            try {
                Properties defaultProps = new Properties();
                defaultProps.setProperty("server.port", "12345");
                defaultProps.setProperty("server.max_threads", "50");
                defaultProps.setProperty("files.directory", "server_files/");
                defaultProps.setProperty("db.url", "jdbc:sqlite:file_logs.db");
                defaultProps.setProperty("server.host", "localhost");
                defaultProps.setProperty("debug.mode", "false");
                defaultProps.setProperty("buffer.size", "32768");
                defaultProps.setProperty("socket.timeout", "120000");

                try (java.io.FileOutputStream out = new java.io.FileOutputStream(configFile)) {
                    defaultProps.store(out, "Optimized configuration for File Sharing System");
                }

                System.out.println("[CONFIG] Created default configuration file: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to create default configuration file: " + e.getMessage());
            }
        }
    }
}