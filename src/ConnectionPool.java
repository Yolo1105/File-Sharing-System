import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ConnectionPool {
    private static final ConnectionPool instance = new ConnectionPool();
    // Initialize logger as static to avoid NPE
    private static final Logger logger = Logger.getInstance();
    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    // Reduced connection limit for better SQLite compatibility
    private final int MAX_CONNECTIONS = 5;
    private final int CONNECTION_TIMEOUT_SECONDS = 30;
    private boolean initialized = false;
    private boolean databaseSchemaInitialized = false;

    private ConnectionPool() {
        // Use System.out for initialization to avoid circular dependency with Logger
        System.out.println("[INIT] Initializing ConnectionPool with database URL: " + Config.getDbUrl());

        dbUrl = Config.getDbUrl();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            // Initialize connections lazily when first requested
            // This avoids circular dependency issues during startup
            System.out.println("[INIT] ConnectionPool initialized without connections. Will create on demand.");

            // Add shutdown hook to close connections
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeAllConnections));

        } catch (Exception e) {
            System.err.println("[FATAL] Database initialization failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public static ConnectionPool getInstance() {
        return instance;
    }

    /**
     * Initializes the database schema - tables, indices, etc.
     * This should be called once at application startup
     */
    public static synchronized void initializeDatabaseSchema() {
        ConnectionPool pool = getInstance();

        // Skip if already initialized
        if (pool.databaseSchemaInitialized) {
            System.out.println("[INFO] Database schema already initialized, skipping");
            return;
        }

        System.out.println("[INFO] Initializing database schema...");
        Connection conn = null;

        try {
            conn = pool.getConnection();

            // Enable foreign keys for SQLite
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
                // Add SQLite optimizations for better concurrency
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                stmt.execute("PRAGMA busy_timeout = 3000");
            }

            // Initialize all required tables
            createFilesTable(conn);
            createLogsTable(conn);

            pool.databaseSchemaInitialized = true;
            System.out.println("[INFO] Database schema initialized successfully");

            // Now that schema is initialized, we can safely log
            try {
                if (logger != null) {
                    logger.log(Logger.Level.INFO, "ConnectionPool", "Database schema initialized successfully");
                }
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to log database initialization: " + e.getMessage());
            }

        } catch (SQLException e) {
            System.err.println("[FATAL] Failed to initialize database schema: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize database schema", e);
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Creates the files table if it doesn't exist
     */
    private static void createFilesTable(Connection conn) throws SQLException {
        boolean tableExists = tableExists(conn, "files");

        if (!tableExists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        filename TEXT NOT NULL UNIQUE,
                        content BLOB NOT NULL,
                        file_size INTEGER NOT NULL,
                        checksum BLOB NOT NULL,
                        upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                System.out.println("[INFO] Created files table with schema");
            }
        } else {
            System.out.println("[INFO] Files table already exists");
        }
    }

    /**
     * Creates the logs table if it doesn't exist
     */
    private static void createLogsTable(Connection conn) throws SQLException {
        boolean tableExists = tableExists(conn, "logs");

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
                System.out.println("[INFO] Created logs table with schema");
            }
        } else {
            System.out.println("[INFO] Logs table already exists");
        }
    }

    /**
     * Checks if a table exists in the database
     */
    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private synchronized void initializeIfNeeded() {
        if (initialized) return;

        try {
            // Create initial connections
            for (int i = 0; i < 2; i++) { // Start with just 2 connections
                connections.add(createConnection());
            }

            System.out.println("[INFO] Created initial database connections");
            initialized = true;

            // Now that connections are initialized, we can safely log using Logger
            try {
                if (logger != null) {
                    logger.log(Logger.Level.INFO, "ConnectionPool",
                            "Connection pool initialized with " + connections.size() + " initial connections");
                }
            } catch (Exception e) {
                // If logger still has issues, just use console
                System.out.println("[INFO] Connection pool initialized with " + connections.size() + " initial connections");
            }

        } catch (SQLException e) {
            System.err.println("[FATAL] Failed to initialize database connections: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize connections", e);
        }
    }

    private Connection createConnection() throws SQLException {
        try {
            Connection conn = DriverManager.getConnection(dbUrl);
            // Set any needed connection properties
            conn.setAutoCommit(true);

            // Apply SQLite optimizations
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA synchronous = NORMAL");
                stmt.execute("PRAGMA busy_timeout = 3000");
            }

            // Test the connection
            if (!conn.isValid(5)) {
                throw new SQLException("Failed to create a valid connection");
            }
            return conn;
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to create database connection: " + e.getMessage());
            throw e;
        }
    }

    public Connection getConnection() throws SQLException {
        // Make sure we're initialized
        initializeIfNeeded();

        for (int attempts = 0; attempts < 3; attempts++) {
            try {
                if (!semaphore.tryAcquire(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new SQLException("Timeout waiting for database connection");
                }

                synchronized (connections) {
                    if (connections.isEmpty()) {
                        // No connections available, create a new one
                        try {
                            return createConnection();
                        } catch (SQLException e) {
                            // Release the permit before rethrowing
                            semaphore.release();
                            throw e;
                        }
                    }

                    Connection conn = connections.remove(connections.size() - 1);

                    if (conn.isClosed() || !conn.isValid(2)) {
                        try {
                            System.out.println("[WARNING] Found invalid connection in pool, creating new one");
                            ResourceUtils.safeClose(conn);
                        } catch (Exception e) {
                            // Ignore errors when closing invalid connection
                        }

                        try {
                            conn = createConnection();
                        } catch (SQLException e) {
                            // Release the permit before rethrowing
                            semaphore.release();
                            throw e;
                        }
                    }

                    return conn;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for database connection", e);
            } catch (SQLException e) {
                if (attempts < 2) {
                    System.err.println("[ERROR] Retrying connection fetch attempt #" + (attempts + 1) + ": " + e.getMessage());
                }
            }
        }

        throw new SQLException("Failed to get a valid connection after retries");
    }

    public void releaseConnection(Connection connection) {
        if (connection == null) {
            return; // Nothing to release
        }

        try {
            // Only return valid connections to the pool
            if (!connection.isClosed() && connection.isValid(2)) {
                synchronized (connections) {
                    if (connections.size() < MAX_CONNECTIONS) {
                        connections.add(connection);
                    } else {
                        // Too many connections, just close this one
                        ResourceUtils.safeClose(connection, "excess connection", logger);
                    }
                }
            } else {
                // If connection is invalid, create a new one to replace it
                ResourceUtils.safeClose(connection, "invalid connection", logger);
                synchronized (connections) {
                    if (connections.size() < MAX_CONNECTIONS) {
                        try {
                            connections.add(createConnection()); // Add a new connection
                        } catch (SQLException e) {
                            logger.log(Logger.Level.ERROR, "ConnectionPool",
                                    "Failed to create replacement connection", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "ConnectionPool",
                    "Error validating connection before release", e);
            ResourceUtils.safeClose(connection);
        } finally {
            // Always release the semaphore
            semaphore.release();
        }
    }

    private void closeAllConnections() {
        System.out.println("[INFO] Closing all database connections");

        synchronized (connections) {
            for (Connection connection : connections) {
                ResourceUtils.safeClose(connection, "database connection", logger);
            }
            connections.clear();
        }

        System.out.println("[INFO] All database connections closed");
    }
}