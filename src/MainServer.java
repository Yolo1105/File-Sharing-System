import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainServer {
    private static final Logger logger = Logger.getInstance();
    private static ServerSocket serverSocket;
    private static ExecutorService pool;
    private static volatile boolean running = true;

    // Track number of active connections
    private static int activeConnections = 0;

    public static void main(String[] args) {
        int port = Config.getServerPort();

        // Get max threads from config, but use a higher default if not specified
        String maxThreadsStr = Config.getProperty("server.max_threads", "50");
        int maxThreadsInt = Integer.parseInt(maxThreadsStr);

        // Set core pool size to a lower value than max to conserve resources
        int corePoolSize = Math.max(5, maxThreadsInt / 2);

        // Initialize database tables AFTER logger is fully initialized
        logger.log(Logger.Level.INFO, "MainServer", "Initializing database...");
        try {
            initializeDatabase();
        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "MainServer", "Failed to initialize database: " + e.getMessage(), e);
            System.exit(1);
        }

        // Create an improved thread pool with a queue
        pool = new ThreadPoolExecutor(
                corePoolSize,                  // Core pool size
                maxThreadsInt,                 // Maximum pool size
                60L, TimeUnit.SECONDS,         // Keep alive time for idle threads
                new LinkedBlockingQueue<>(100), // Queue for waiting tasks
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy() // If queue is full, caller thread executes the task
        );

        logger.log(Logger.Level.INFO, "MainServer",
                "Starting server on port " + port + " with " + corePoolSize +
                        " core threads, " + maxThreadsInt + " max threads");

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));

        try {
            serverSocket = new ServerSocket(port);
            // Set a timeout so we can check if we need to shut down
            serverSocket.setSoTimeout(1000); // 1 second timeout

            logger.log(Logger.Level.INFO, "MainServer", "Server started successfully");

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();

                    // Log active connection count
                    synchronized (MainServer.class) {
                        activeConnections++;
                        logger.log(Logger.Level.INFO, "MainServer",
                                "New connection accepted from: " + clientSocket.getRemoteSocketAddress() +
                                        " (Active connections: " + activeConnections + ")");
                    }

                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                new ClientHandler(clientSocket).run();
                            } finally {
                                // Decrement connection count when client handler finishes
                                synchronized (MainServer.class) {
                                    activeConnections--;
                                    logger.log(Logger.Level.INFO, "MainServer",
                                            "Connection closed. Active connections: " + activeConnections);
                                }
                            }
                        }
                    });
                } catch (SocketTimeoutException e) {
                    // This is normal - it allows us to periodically check if we should shut down
                } catch (IOException e) {
                    if (running) {
                        logger.log(Logger.Level.ERROR, "MainServer", "Error accepting client connection", e);
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "MainServer", "Could not start server on port " + port, e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    /**
     * Initializes required database tables for file storage
     */
    private static void initializeDatabase() {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();

            try (Statement stmt = conn.createStatement()) {
                // Create files table if it doesn't exist
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        filename TEXT NOT NULL UNIQUE,
                        content BLOB NOT NULL,
                        file_size INTEGER NOT NULL,
                        checksum BLOB NOT NULL,
                        upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                logger.log(Logger.Level.INFO, "MainServer", "Database file storage initialized");
            }
        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "MainServer", "Failed to initialize database tables", e);
            throw new RuntimeException("Failed to initialize database", e);
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Shutdowns the server gracefully
     */
    private static void shutdown() {
        logger.log(Logger.Level.INFO, "MainServer", "Server shutting down...");

        // Mark as not running
        running = false;

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "MainServer", "Error closing server socket", e);
            }
        }

        // Shutdown thread pool
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
            try {
                // Wait for existing tasks to terminate
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    // Force shutdown if still executing after timeout
                    pool.shutdownNow();
                    if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                        logger.log(Logger.Level.ERROR, "MainServer", "Thread pool did not terminate");
                    }
                }
            } catch (InterruptedException e) {
                // (Re-)Cancel if current thread also interrupted
                pool.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }

        logger.log(Logger.Level.INFO, "MainServer", "Server shutdown complete");
    }

    // Method to get current active connections (can be useful for monitoring)
    public static synchronized int getActiveConnections() {
        return activeConnections;
    }
}