import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ConnectionPool {
    private static final ConnectionPool instance = new ConnectionPool();
    private static final Logger logger = Logger.getInstance();
    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    private final int MAX_CONNECTIONS = 10;
    private final int CONNECTION_TIMEOUT_SECONDS = 30;

    private ConnectionPool() {
        dbUrl = Config.getDbUrl();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                connections.add(createConnection());
            }
            logger.log(Logger.Level.INFO, "ConnectionPool", "Connection pool initialized with " + MAX_CONNECTIONS + " connections");
        } catch (SQLException e) {
            logger.log(Logger.Level.FATAL, "ConnectionPool", "Failed to initialize connection pool", e);
            throw new RuntimeException("Database initialization failed", e);
        }

        // Add shutdown hook to close connections
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAllConnections));
    }

    public static ConnectionPool getInstance() {
        return instance;
    }

    private Connection createConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        // Set any needed connection properties
        conn.setAutoCommit(true);
        // Test the connection
        if (!conn.isValid(5)) {
            throw new SQLException("Failed to create a valid connection");
        }
        return conn;
    }

    public Connection getConnection() throws SQLException {
        for (int attempts = 0; attempts < 3; attempts++) {
            try {
                if (!semaphore.tryAcquire(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new SQLException("Timeout waiting for database connection");
                }

                synchronized (connections) {
                    if (connections.isEmpty()) {
                        return createConnection(); // fallback
                    }

                    Connection conn = connections.remove(connections.size() - 1);

                    if (conn.isClosed() || !conn.isValid(2)) {
                        logger.log(Logger.Level.WARNING, "ConnectionPool", "Found invalid connection in pool, creating new one");
                        conn.close();
                        conn = createConnection();
                    }

                    return conn;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for database connection", e);
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "ConnectionPool", "Retrying connection fetch attempt #" + (attempts + 1), e);
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
                    connections.add(connection);
                }
            } else {
                // If connection is invalid, create a new one to replace it
                try {
                    connection.close(); // Close the bad connection
                    synchronized (connections) {
                        connections.add(createConnection()); // Add a new connection
                    }
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "ConnectionPool", "Failed to create replacement connection", e);
                }
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "ConnectionPool", "Error validating connection before release", e);
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
        synchronized (connections) {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.log(Logger.Level.ERROR, "ConnectionPool", "Error closing connection", e);
                }
            }
            connections.clear();
        }
        logger.log(Logger.Level.INFO, "ConnectionPool", "All database connections closed");
    }
}