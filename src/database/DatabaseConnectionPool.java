package database;

import logs.Logger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import utils.ResourceUtils;
import config.Config;

public class DatabaseConnectionPool {
    private static final DatabaseConnectionPool instance = new DatabaseConnectionPool();
    private static final Logger logger = Logger.getInstance();
    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    private final int MAX_CONNECTIONS;
    private final int CONNECTION_TIMEOUT_SECONDS;
    private boolean initialized = false;
    private boolean databaseSchemaInitialized = false;

    private DatabaseConnectionPool() {
        System.out.println("[INIT] Initializing ConnectionPool with database URL: " + Config.getDbUrl());

        dbUrl = Config.getDbUrl();
        MAX_CONNECTIONS = Config.getDbMaxConnections();
        CONNECTION_TIMEOUT_SECONDS = Config.getDbConnectionTimeout();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            // Initialize connections lazily when first requested
            System.out.println("[INIT] ConnectionPool initialized without connections. Will create on demand.");

            // Add shutdown hook to close connections
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeAllConnections));
        } catch (Exception e) {
            System.err.println("[FATAL] Database initialization failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public static DatabaseConnectionPool getInstance() {
        return instance;
    }

    public static synchronized void initializeDatabaseSchema() {
        DatabaseConnectionPool pool = getInstance();

        // Skip if already initialized
        if (pool.databaseSchemaInitialized) {
            System.out.println("[INFO] Database schema already initialized, skipping");
            return;
        }

        System.out.println("[INFO] Initializing database schema...");
        Connection conn = null;

        try {
            conn = pool.getConnection();

            // Apply SQLite optimizations
            applySQLitePragmas(conn);

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

    // Utility method for applying SQLite optimizations - consolidated from multiple places
    public static void applySQLitePragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA busy_timeout = 3000");
        }
    }

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

    public static boolean tableExists(Connection conn, String tableName) throws SQLException {
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
            logger.log(Logger.Level.INFO, "ConnectionPool",
                    "Connection pool initialized with " + connections.size() + " initial connections");
        } catch (SQLException e) {
            System.err.println("[FATAL] Failed to initialize database connections: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize connections", e);
        }
    }

    private Connection createConnection() throws SQLException {
        try {
            Connection conn = DriverManager.getConnection(dbUrl);
            conn.setAutoCommit(true);

            // Apply SQLite optimizations
            applySQLitePragmas(conn);

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
                        ResourceUtils.safeClose(conn);
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

    // UTILITY METHODS FOR DATABASE OPERATIONS
    // Centralized to avoid duplicate implementations

    // Execute a database operation that doesn't return results
    public static void executeWithConnection(Consumer<Connection> action, String logSource) throws SQLException {
        DatabaseConnectionPool pool = getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            action.accept(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Database operation failed: " + e.getMessage(), e);
            throw e;
        } finally {
            pool.releaseConnection(conn);
        }
    }

    // Execute a database query that returns a result
    public static <T> T queryWithConnection(Function<Connection, T> function, T defaultValue, String logSource) {
        DatabaseConnectionPool pool = getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            return function.apply(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Connection operation failed: " + e.getMessage(), e);
            return defaultValue;
        } finally {
            pool.releaseConnection(conn);
        }
    }

    // Execute a query that returns a list of objects
    public static <T> List<T> queryList(String query, Function<ResultSet, T> rowMapper, String logSource) {
        return queryWithConnection(conn -> {
            List<T> results = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    results.add(rowMapper.apply(rs));
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, logSource, "Error executing query: " + e.getMessage(), e);
            }
            return results;
        }, new ArrayList<>(), logSource);
    }

    // Execute a database transaction
    public static void executeTransaction(Consumer<Connection> transaction, String logSource) {
        try {
            executeWithConnection(conn -> {
                boolean originalAutoCommit = false;
                try {
                    originalAutoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    transaction.accept(conn);
                    conn.commit();
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        logger.log(Logger.Level.ERROR, logSource, "Error during rollback", rollbackEx);
                    }
                    // Use the standard logging pattern instead of custom handling
                    logger.log(Logger.Level.ERROR, logSource, "Transaction failed: " + e.getMessage(), e);
                    // Re-throw as RuntimeException to be consistent with executeWithConnection pattern
                    throw new RuntimeException(e);
                } finally {
                    try {
                        conn.setAutoCommit(originalAutoCommit);
                    } catch (SQLException resetEx) {
                        logger.log(Logger.Level.ERROR, logSource, "Error resetting auto-commit", resetEx);
                    }
                }
            }, logSource);
        } catch (SQLException e) {
            // This catch block is now handling the exception from executeWithConnection
            logger.log(Logger.Level.ERROR, logSource, "Database operation failed in transaction", e);
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