package config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class Config {
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "config.properties";
    private static final ConcurrentHashMap<String, Object> propertyCache = new ConcurrentHashMap<>();

    private static final int DEFAULT_BUFFER_SIZE = 32768;
    private static final int DEFAULT_SOCKET_TIMEOUT = 120000;
    private static final int DEFAULT_SERVER_PORT = 9000;
    private static final int DEFAULT_MAX_THREADS = 50;
    private static final String DEFAULT_DB_URL = "jdbc:sqlite:file_storage.db";
    private static final int DEFAULT_DB_MAX_CONNECTIONS = 5;
    private static final int DEFAULT_DB_CONNECTION_TIMEOUT = 30;
    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024; 
    private static final String DEFAULT_DOWNLOADS_DIR = "downloads/";
    private static final int DEFAULT_CORE_POOL_SIZE = 10;

    public static final class Protocol {
        public static final String CMD_UPLOAD = "UPLOAD";
        public static final String CMD_DOWNLOAD = "DOWNLOAD";
        public static final String CMD_DELETE = "DELETE";
        public static final String CMD_LIST = "LIST";
        public static final String CMD_LOGS = "LOGS";
        public static final String RESPONSE_END_MARKER = "*END*";
        public static final String CLIENT_ID_PREFIX = "CLIENT_ID ";
        public static final String NOTIFICATION_PREFIX = "SERVER_NOTIFICATION:";
    }

    static {
        boolean loadedConfig = false;
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (InputStream input = new java.io.FileInputStream(configFile)) {
                properties.load(input);
                System.out.println("[CONFIG] Configuration loaded from file: " + configFile.getAbsolutePath());
                loadedConfig = true;
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to loading configuration from file: " + e.getMessage());
            }
        }

        if (!loadedConfig) {
            try (InputStream input = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (input == null) {
                    System.err.println("[CONFIG] Could not find " + CONFIG_FILE + ", using defaults");
                    setup();
                } else {
                    properties.load(input);
                    loadedConfig = true;
                }
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to loading configuration: " + e.getMessage());
                setup();
            }
        }
        optimizeProperties();
        System.out.println("[CONFIG] Server port: " + getServerPort());
        System.out.println("[CONFIG] Max threads: " + getMaxThreads());
        System.out.println("[CONFIG] DB URL: " + getDbUrl());
    }

    private static void setup() {
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

    private static void optimizeProperties() {
        int bufferSize = getIntProperty("buffer.size", DEFAULT_BUFFER_SIZE);
        if (bufferSize < 8192 || (bufferSize & (bufferSize - 1)) != 0) {
            int optimizedSize = 8192;
            while (optimizedSize < bufferSize) {
                optimizedSize *= 2;
            }
            properties.setProperty("buffer.size", String.valueOf(optimizedSize));
            System.out.println("[CONFIG] Optimized buffer.size from " + bufferSize + " to " + optimizedSize);
        }

        int socketTimeout = getIntProperty("socket.timeout", 30000);
        if (socketTimeout < 30000) {
            properties.setProperty("socket.timeout", "30000");
            System.out.println("[CONFIG] Increased socket.timeout to minimum 30000ms");
        }
    }

    public static int getIntProperty(String key, int defaultValue) {
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

    public static long getLongProperty(String key, long defaultValue) {
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

    public static int getServerPort() {
        return getIntProperty("server.port", DEFAULT_SERVER_PORT);
    }

    public static int getMaxThreads() {
        return getIntProperty("server.max_threads", DEFAULT_MAX_THREADS);
    }

    public static int getBufferSize() {
        return getIntProperty("buffer.size", DEFAULT_BUFFER_SIZE);
    }

    public static int getSocketTimeout() {
        return getIntProperty("socket.timeout", DEFAULT_SOCKET_TIMEOUT);
    }

    public static int getFileTransferTimeout() {
        return getSocketTimeout() * 2; 
    }

    public static int getThreadCount() {
        return getIntProperty("server.core_pool_size", DEFAULT_CORE_POOL_SIZE);
    }

    public static String getDbUrl() {
        if (propertyCache.containsKey("db.url")) {
            return (String) propertyCache.get("db.url");
        }

        String url = properties.getProperty("db.url", DEFAULT_DB_URL);
        propertyCache.put("db.url", url);
        return url;
    }

    public static int getDbMaxConnections() {
        return getIntProperty("db.max_connections", DEFAULT_DB_MAX_CONNECTIONS);
    }

    public static int getDbConnectionTimeout() {
        return getIntProperty("db.connection_timeout", DEFAULT_DB_CONNECTION_TIMEOUT);
    }

    public static long getMaxFileSize() {
        return getLongProperty("file.max_size", DEFAULT_MAX_FILE_SIZE);
    }

    public static String getDownloadsDir() {
        return getProperty("downloads.dir", DEFAULT_DOWNLOADS_DIR);
    }

    public static String getProperty(String key, String defaultValue) {
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

    public static String getServerHost() {
        return getProperty("server.host", "localhost");
    }

    public static boolean ServiceConnectionCheck(String clientName) {
        return clientName != null &&
                (clientName.contains("_upload") ||
                        clientName.contains("_download") ||
                        clientName.contains("_verify"));
    }
}