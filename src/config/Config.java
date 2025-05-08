package config;

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

    // Standardized constants for the application
    private static final int DEFAULT_BUFFER_SIZE = 32768;
    private static final int DEFAULT_SOCKET_TIMEOUT = 120000;
    private static final int DEFAULT_SERVER_PORT = 9000;
    private static final int DEFAULT_MAX_THREADS = 50;
    private static final String DEFAULT_DB_URL = "jdbc:sqlite:file_storage.db";
    private static final int DEFAULT_DB_MAX_CONNECTIONS = 5;
    private static final int DEFAULT_DB_CONNECTION_TIMEOUT = 30;
    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final String DEFAULT_DOWNLOADS_DIR = "downloads/";
    private static final int DEFAULT_CORE_POOL_SIZE = 10;

    // Protocol constants for client-server communication
    public static final class Protocol {
        public static final String CMD_UPLOAD = "UPLOAD";
        public static final String CMD_DOWNLOAD = "DOWNLOAD";
        public static final String CMD_LIST = "LIST";
        public static final String CMD_LOGS = "LOGS";
        public static final String RESPONSE_END_MARKER = "*END*";
        public static final String CLIENT_ID_PREFIX = "CLIENT_ID ";
        public static final String NOTIFICATION_PREFIX = "SERVER_NOTIFICATION:";
    }

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
        System.out.println("[CONFIG] DB URL: " + getDbUrl());
        System.out.println("[CONFIG] Debug mode: " + getProperty("debug.mode", "false"));
        System.out.println("[CONFIG] Buffer size: " + getBufferSize());
        System.out.println("[CONFIG] Socket timeout: " + getSocketTimeout());
        System.out.println("[CONFIG] Max file size: " + getMaxFileSize() + " bytes");
        System.out.println("[CONFIG] DB max connections: " + getDbMaxConnections());
    }

    private static void setDefaults() {
        properties.setProperty("server.port", String.valueOf(DEFAULT_SERVER_PORT));
        properties.setProperty("server.max_threads", String.valueOf(DEFAULT_MAX_THREADS));
        properties.setProperty("server.core_pool_size", String.valueOf(DEFAULT_CORE_POOL_SIZE));
        properties.setProperty("db.url", DEFAULT_DB_URL);
        properties.setProperty("db.max_connections", String.valueOf(DEFAULT_DB_MAX_CONNECTIONS));
        properties.setProperty("db.connection_timeout", String.valueOf(DEFAULT_DB_CONNECTION_TIMEOUT));
        properties.setProperty("server.host", "localhost");
        properties.setProperty("debug.mode", "false");
        properties.setProperty("buffer.size", String.valueOf(DEFAULT_BUFFER_SIZE));
        properties.setProperty("socket.timeout", String.valueOf(DEFAULT_SOCKET_TIMEOUT));
        properties.setProperty("file.max_size", String.valueOf(DEFAULT_MAX_FILE_SIZE));
        properties.setProperty("downloads.dir", DEFAULT_DOWNLOADS_DIR);
        System.out.println("[CONFIG] Default configuration set");
    }

    /**
     * Optimize property values for better performance
     */
    private static void optimizeProperties() {
        // Ensure buffer sizes are power of 2 and not too small
        int bufferSize = getIntProperty("buffer.size", DEFAULT_BUFFER_SIZE);
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

    /**
     * Gets a long property with default value
     */
    public static long getLongProperty(String key, long defaultValue) {
        // Check cache first
        if (propertyCache.containsKey(key)) {
            Object value = propertyCache.get(key);
            if (value instanceof Long) {
                return (Long) value;
            }
        }

        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }

        try {
            long result = Long.parseLong(value);
            propertyCache.put(key, result);
            return result;
        } catch (NumberFormatException e) {
            System.err.println("[CONFIG] Invalid long property: " + key + " = " + value);
            return defaultValue;
        }
    }

    /**
     * Gets a boolean property with default value
     */
    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        // Check cache first
        if (propertyCache.containsKey(key)) {
            Object value = propertyCache.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        }

        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }

        boolean result = Boolean.parseBoolean(value);
        propertyCache.put(key, result);
        return result;
    }

    /**
     * Gets the server port from configuration
     */
    public static int getServerPort() {
        return getIntProperty("server.port", DEFAULT_SERVER_PORT);
    }

    /**
     * Gets the maximum number of threads for the server
     */
    public static int getMaxThreads() {
        return getIntProperty("server.max_threads", DEFAULT_MAX_THREADS);
    }

    /**
     * Gets the buffer size for I/O operations
     */
    public static int getBufferSize() {
        return getIntProperty("buffer.size", DEFAULT_BUFFER_SIZE);
    }

    /**
     * Gets the socket timeout in milliseconds
     */
    public static int getSocketTimeout() {
        return getIntProperty("socket.timeout", DEFAULT_SOCKET_TIMEOUT);
    }

    /**
     * Gets the upload/download timeout (typically longer than regular timeout)
     */
    public static int getFileTransferTimeout() {
        return getSocketTimeout() * 2; // Double the regular timeout for file transfers
    }

    /**
     * Gets the core pool size for thread pool
     */
    public static int getCorePoolSize() {
        return getIntProperty("server.core_pool_size", DEFAULT_CORE_POOL_SIZE);
    }

    /**
     * Gets the database URL
     */
    public static String getDbUrl() {
        // Check cache first
        if (propertyCache.containsKey("db.url")) {
            return (String) propertyCache.get("db.url");
        }

        String url = properties.getProperty("db.url", DEFAULT_DB_URL);
        propertyCache.put("db.url", url);
        return url;
    }

    /**
     * Gets the maximum number of database connections
     */
    public static int getDbMaxConnections() {
        return getIntProperty("db.max_connections", DEFAULT_DB_MAX_CONNECTIONS);
    }

    /**
     * Gets the database connection timeout in seconds
     */
    public static int getDbConnectionTimeout() {
        return getIntProperty("db.connection_timeout", DEFAULT_DB_CONNECTION_TIMEOUT);
    }

    /**
     * Gets the maximum allowed file size
     */
    public static long getMaxFileSize() {
        return getLongProperty("file.max_size", DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * Gets the downloads directory
     */
    public static String getDownloadsDir() {
        return getProperty("downloads.dir", DEFAULT_DOWNLOADS_DIR);
    }

    /**
     * Gets a string property with default value
     */
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
     * Gets the server host address
     */
    public static String getServerHost() {
        return getProperty("server.host", "localhost");
    }

    /**
     * Checks if debug mode is enabled
     */
    public static boolean isDebugMode() {
        return getBooleanProperty("debug.mode", false);
    }

    /**
     * Utility method to check if a client connection is a utility connection
     * (for upload, download or verification operations)
     */
    public static boolean isUtilityConnection(String clientName) {
        return clientName != null &&
                (clientName.contains("_upload") ||
                        clientName.contains("_download") ||
                        clientName.contains("_verify"));
    }

    /**
     * Creates a config.properties file with optimized default values if it doesn't exist
     */
    public static void createDefaultConfigFile() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            try {
                Properties defaultProps = new Properties();
                defaultProps.setProperty("server.port", String.valueOf(DEFAULT_SERVER_PORT));
                defaultProps.setProperty("server.max_threads", String.valueOf(DEFAULT_MAX_THREADS));
                defaultProps.setProperty("server.core_pool_size", String.valueOf(DEFAULT_CORE_POOL_SIZE));
                defaultProps.setProperty("db.url", DEFAULT_DB_URL);
                defaultProps.setProperty("db.max_connections", String.valueOf(DEFAULT_DB_MAX_CONNECTIONS));
                defaultProps.setProperty("db.connection_timeout", String.valueOf(DEFAULT_DB_CONNECTION_TIMEOUT));
                defaultProps.setProperty("server.host", "localhost");
                defaultProps.setProperty("debug.mode", "false");
                defaultProps.setProperty("buffer.size", String.valueOf(DEFAULT_BUFFER_SIZE));
                defaultProps.setProperty("socket.timeout", String.valueOf(DEFAULT_SOCKET_TIMEOUT));
                defaultProps.setProperty("file.max_size", String.valueOf(DEFAULT_MAX_FILE_SIZE));
                defaultProps.setProperty("downloads.dir", DEFAULT_DOWNLOADS_DIR);

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