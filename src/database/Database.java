package database;

import config.Config;
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
import logs.Logger;
import utils.IOProcessor;

public class Database {
    private static final Database instance = new Database();
    private static final Logger logger = Logger.getInstance();

    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    private final int MAX_CONNECTIONS;
    private final int CONNECTION_TIMEOUT_SECONDS;

    private boolean initialized = false;
    private boolean initializedDatabaseSchema = false;

    private Database() {
        System.out.println("[INIT] Initializing ConnectionPool with database URL: " + Config.getDbUrl());

        dbUrl = Config.getDbUrl();
        MAX_CONNECTIONS = Config.getDbMaxConnections();
        CONNECTION_TIMEOUT_SECONDS = Config.getDbConnectionTimeout();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            System.out.println("[INIT] ConnectionPool initialized without connections. Will create on demand.");
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeAllConnections));
        } catch (Exception e) {
            System.err.println("[FATAL] Database initialization failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public static Database getInstance() {
        return instance;
    }

    public static synchronized void initializeDatabaseSchema() {
        Database pool = getInstance();

        if (pool.initializedDatabaseSchema) {
            System.out.println("[INFO] Database schema already initialized, skipping");
            return;
        }

        System.out.println("[INFO] Initializing database schema...");
        Connection con_stat = null;

        try {
            con_stat = pool.getConnection();
            applySQLitePragmas(con_stat);
            createFilesTable(con_stat);
            createLogsTable(con_stat);

            pool.initializedDatabaseSchema = true;
            System.out.println("[INFO] Database schema initialized successfully");

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
            pool.releaseConnection(con_stat);
        }
    }

    public static void applySQLitePragmas(Connection con_stat) throws SQLException {
        try (Statement stmt = con_stat.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA busy_timeout = 3000");
        }
    }

    private static void createFilesTable(Connection con_stat) throws SQLException {
        boolean tableExists = tableExists(con_stat, "files");

        if (!tableExists) {
            try (Statement stmt = con_stat.createStatement()) {
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

    private static void createLogsTable(Connection con_stat) throws SQLException {
        boolean tableExists = tableExists(con_stat, "logs");

        if (!tableExists) {
            try (Statement stmt = con_stat.createStatement()) {
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

    public static boolean tableExists(Connection con_stat, String tableName) throws SQLException {
        try (ResultSet rs = con_stat.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private synchronized void initializeCheck() {
        if (initialized) return;

        try {
            for (int i = 0; i < 2; i++) {
                connections.add(createConnection());
            }

            System.out.println("[INFO] Created initial database connections");
            initialized = true;

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
            Connection con_stat = DriverManager.getConnection(dbUrl);
            con_stat.setAutoCommit(true);
            applySQLitePragmas(con_stat);

            if (!con_stat.isValid(5)) {
                throw new SQLException("Failed to create a valid connection");
            }
            return con_stat;
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to create database connection: " + e.getMessage());
            throw e;
        }
    }

    public Connection getConnection() throws SQLException {
        initializeCheck();

        for (int attempts = 0; attempts < 3; attempts++) {
            try {
                if (!semaphore.tryAcquire(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new SQLException("Timeout waiting for database connection");
                }

                synchronized (connections) {
                    if (connections.isEmpty()) {
                        try {
                            return createConnection();
                        } catch (SQLException e) {
                            semaphore.release();
                            throw e;
                        }
                    }

                    Connection con_stat = connections.remove(connections.size() - 1);

                    if (con_stat.isClosed() || !con_stat.isValid(2)) {
                        IOProcessor.closeCheck(con_stat);
                        try {
                            con_stat = createConnection();
                        } catch (SQLException e) {
                            semaphore.release();
                            throw e;
                        }
                    }
                    return con_stat;
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
            return;
        }

        try {
            if (!connection.isClosed() && connection.isValid(2)) {
                synchronized (connections) {
                    if (connections.size() < MAX_CONNECTIONS) {
                        connections.add(connection);
                    } else {
                        IOProcessor.closeCheck(connection, "excess connection", logger);
                    }
                }
            } else {
                IOProcessor.closeCheck(connection, "invalid connection", logger);
                synchronized (connections) {
                    if (connections.size() < MAX_CONNECTIONS) {
                        try {
                            connections.add(createConnection());
                        } catch (SQLException e) {
                            logger.log(Logger.Level.ERROR, "ConnectionPool",
                                    "Failed to create replacement connection", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "ConnectionPool",
                    "Failed to validating connection before release", e);
            IOProcessor.closeCheck(connection);
        } finally {
            semaphore.release();
        }
    }

    public static void executeWithConnection(Consumer<Connection> action, String logSource) throws SQLException {
        Database pool = getInstance();
        Connection con_stat = null;

        try {
            con_stat = pool.getConnection();
            action.accept(con_stat);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Database operation failed: " + e.getMessage(), e);
            throw e;
        } finally {
            pool.releaseConnection(con_stat);
        }
    }

    public static <T> T queryConnection(Function<Connection, T> function, T defaultValue, String logSource) {
        Database pool = getInstance();
        Connection con_stat = null;

        try {
            con_stat = pool.getConnection();
            return function.apply(con_stat);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Connection operation failed: " + e.getMessage(), e);
            return defaultValue;
        } finally {
            pool.releaseConnection(con_stat);
        }
    }

    private void closeAllConnections() {
        System.out.println("[INFO] Closing all database connections");

        synchronized (connections) {
            for (Connection connection : connections) {
                IOProcessor.closeCheck(connection, "database connection", logger);
            }
            connections.clear();
        }

        System.out.println("[INFO] All database connections closed");
    }
}