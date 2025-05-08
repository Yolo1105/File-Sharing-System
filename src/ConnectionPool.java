import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ConnectionPool {
    private static final ConnectionPool instance = new ConnectionPool();
    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    private final int MAX_CONNECTIONS = 10;
    private final int CONNECTION_TIMEOUT_SECONDS = 30;
    private boolean initialized = false;

    private ConnectionPool() {
        // First, print initialization message to console for debugging
        System.out.println("[INIT] Initializing ConnectionPool with database URL: " + Config.getDbUrl());

        dbUrl = Config.getDbUrl();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            // Initialize connections lazily when first requested
            // This avoids circular dependency issues during startup
            // We'll create the first connection in getConnection()
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

    private synchronized void initializeIfNeeded() {
        if (initialized) return;

        try {
            // Create initial connections
            for (int i = 0; i < 2; i++) { // Start with just 2 connections
                connections.add(createConnection());
            }

            System.out.println("[INIT] Created initial database connections");
            initialized = true;

            // Now that it's safe, log the initialization through the Logger
            try {
                Logger.getInstance().log(Logger.Level.INFO, "ConnectionPool",
                        "Connection pool initialized with " + connections.size() + " initial connections");
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
                            conn.close();
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
                        try {
                            connection.close();
                        } catch (SQLException e) {
                            System.err.println("[ERROR] Error closing excess connection: " + e.getMessage());
                        }
                    }
                }
            } else {
                // If connection is invalid, create a new one to replace it
                try {
                    connection.close(); // Close the bad connection
                    synchronized (connections) {
                        if (connections.size() < MAX_CONNECTIONS) {
                            try {
                                connections.add(createConnection()); // Add a new connection
                            } catch (SQLException e) {
                                System.err.println("[ERROR] Failed to create replacement connection: " + e.getMessage());
                            }
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("[ERROR] Error closing invalid connection: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Error validating connection before release: " + e.getMessage());
            try {
                connection.close();
            } catch (SQLException ex) {
                // Ignore, we're already handling an error
            }
        } finally {
            // Always release the semaphore
            semaphore.release();
        }
    }

    private void closeAllConnections() {
        System.out.println("[INFO] Closing all database connections");

        synchronized (connections) {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    System.err.println("[ERROR] Error closing connection: " + e.getMessage());
                }
            }
            connections.clear();
        }

        System.out.println("[INFO] All database connections closed");
    }
}