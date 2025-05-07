import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class ConnectionPool {
    private static final ConnectionPool instance = new ConnectionPool();
    private final String dbUrl;
    private final List<Connection> connections;
    private final Semaphore semaphore;
    private final int MAX_CONNECTIONS = 10;

    private ConnectionPool() {
        dbUrl = Config.getDbUrl();
        connections = new ArrayList<>(MAX_CONNECTIONS);
        semaphore = new Semaphore(MAX_CONNECTIONS, true);

        try {
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                connections.add(createConnection());
            }
            System.out.println("[DB] Connection pool initialized with " + MAX_CONNECTIONS + " connections");
        } catch (SQLException e) {
            System.err.println("[DB] Failed to initialize connection pool: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }

        // Add shutdown hook to close connections
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAllConnections));
    }

    public static ConnectionPool getInstance() {
        return instance;
    }

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    public Connection getConnection() throws SQLException {
        try {
            semaphore.acquire();
            synchronized (connections) {
                if (connections.isEmpty()) {
                    return createConnection(); // Should not happen if semaphore works correctly
                }
                return connections.remove(connections.size() - 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for database connection", e);
        }
    }

    public void releaseConnection(Connection connection) {
        synchronized (connections) {
            connections.add(connection);
        }
        semaphore.release();
    }

    private void closeAllConnections() {
        synchronized (connections) {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    System.err.println("[DB] Error closing connection: " + e.getMessage());
                }
            }
            connections.clear();
        }
        System.out.println("[DB] All database connections closed");
    }
}