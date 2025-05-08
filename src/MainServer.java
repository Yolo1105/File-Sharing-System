import logs.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicInteger;

import network.ClientHandler;
import config.Config;
import database.ConnectionManager;

public class MainServer {
    private static final Logger logger = Logger.getInstance();
    private static ServerSocket serverSocket;
    private static ExecutorService threadPool;
    private static volatile boolean running = true;

    // Error messages
    private static final String ERR_START_SERVER = "Could not start server on port";
    private static final String ERR_ACCEPT_CONNECTION = "Error accepting client connection";
    private static final String ERR_CLOSE_SOCKET = "Error closing server socket";
    private static final String ERR_THREAD_POOL = "Thread pool did not terminate";

    // Info messages
    private static final String INFO_STARTING = "Starting server on port %d with %d core threads, %d max threads";
    private static final String INFO_STARTED = "Server started successfully";
    private static final String INFO_SHUTDOWN = "Server shutting down...";
    private static final String INFO_SHUTDOWN_COMPLETE = "Server shutdown complete";
    private static final String INFO_NEW_CONNECTION = "New connection accepted from: %s (Active connections: %d)";
    private static final String INFO_CONNECTION_CLOSED = "Connection closed. Active connections: %d";

    // Track number of active connections
    private static final AtomicInteger activeConnections = new AtomicInteger(0);

    public static void main(String[] args) {
        try {
            // Print startup message
            System.out.println("[INFO] [MainServer] Initializing server...");

            int port = Config.getServerPort();
            int maxThreads = Config.getMaxThreads();
            int corePoolSize = Config.getCorePoolSize();

            // Initialize the logging system first
            if (logger != null) {
                logger.setLogLevel(Config.isDebugMode() ? Logger.Level.DEBUG : Logger.Level.INFO);
                logger.log(Logger.Level.INFO, "MainServer", "Initializing server...");
            }

            // Initialize database schema through the connection pool - use try-catch to handle initialization errors
            try {
                ConnectionManager.initializeDatabaseSchema();
            } catch (Exception e) {
                System.err.println("[FATAL] Failed to initialize database schema: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            // Create an improved thread pool with a wait policy instead of CallerRunsPolicy
            RejectedExecutionHandler waitPolicy = (r, executor) -> {
                try {
                    // Wait for space in the queue rather than rejecting outright
                    // This prevents the main thread from executing the task directly
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.log(Logger.Level.ERROR, "MainServer",
                            "Thread interrupted while waiting to queue task");
                }
            };

            threadPool = new ThreadPoolExecutor(
                    corePoolSize,                    // Core pool size
                    maxThreads,                      // Maximum pool size
                    60L, TimeUnit.SECONDS,           // Keep alive time for idle threads
                    new LinkedBlockingQueue<>(100),  // Queue for waiting tasks
                    Executors.defaultThreadFactory(),
                    waitPolicy                       // Custom rejection policy
            );

            System.out.println(String.format(INFO_STARTING, port, corePoolSize, maxThreads));
            if (logger != null) {
                logger.log(Logger.Level.INFO, "MainServer",
                        String.format(INFO_STARTING, port, corePoolSize, maxThreads));
            }

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(MainServer::shutdown));

            // Create the server socket and start accepting connections
            serverSocket = new ServerSocket(port);
            // Set a timeout so we can check if we need to shut down
            serverSocket.setSoTimeout(1000); // 1 second timeout

            System.out.println("[INFO] [MainServer] " + INFO_STARTED);
            if (logger != null) {
                logger.log(Logger.Level.INFO, "MainServer", INFO_STARTED);
            }

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();

                    // Track connection and submit to thread pool
                    int currentConnections = activeConnections.incrementAndGet();
                    System.out.println(String.format(INFO_NEW_CONNECTION,
                            clientSocket.getRemoteSocketAddress(), currentConnections));
                    if (logger != null) {
                        logger.log(Logger.Level.INFO, "MainServer",
                                String.format(INFO_NEW_CONNECTION,
                                        clientSocket.getRemoteSocketAddress(), currentConnections));
                    }

                    threadPool.execute(() -> {
                        try {
                            new ClientHandler(clientSocket).run();
                        } finally {
                            // Decrement connection count when client handler finishes
                            int remaining = activeConnections.decrementAndGet();
                            if (logger != null) {
                                logger.log(Logger.Level.INFO, "MainServer",
                                        String.format(INFO_CONNECTION_CLOSED, remaining));
                            }
                        }
                    });
                } catch (SocketTimeoutException e) {
                    // This is normal - it allows us to periodically check if we should shut down
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[ERROR] " + ERR_ACCEPT_CONNECTION + ": " + e.getMessage());
                        if (logger != null) {
                            logger.log(Logger.Level.ERROR, "MainServer", ERR_ACCEPT_CONNECTION, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            int port = Config.getServerPort();
            System.err.println("[FATAL] " + ERR_START_SERVER + " " + port + ": " + e.getMessage());
            e.printStackTrace();
            if (logger != null) {
                logger.log(Logger.Level.FATAL, "MainServer", ERR_START_SERVER + " " + port, e);
            }
            System.exit(1);
        } catch (Exception e) {
            // Catch any other startup errors
            System.err.println("[FATAL] Unexpected error during server startup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    private static void shutdown() {
        System.out.println("[INFO] " + INFO_SHUTDOWN);
        if (logger != null) {
            logger.log(Logger.Level.INFO, "MainServer", INFO_SHUTDOWN);
        }

        // Prevent multiple shutdown attempts
        if (!running) {
            return;
        }

        // Mark as not running
        running = false;

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("[ERROR] " + ERR_CLOSE_SOCKET + ": " + e.getMessage());
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "MainServer", ERR_CLOSE_SOCKET, e);
                }
            }
        }

        // Shutdown thread pool
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                // Wait for existing tasks to terminate
                if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    // Force shutdown if still executing after timeout
                    threadPool.shutdownNow();
                    if (!threadPool.awaitTermination(15, TimeUnit.SECONDS)) {
                        System.err.println("[ERROR] " + ERR_THREAD_POOL);
                        if (logger != null) {
                            logger.log(Logger.Level.ERROR, "MainServer", ERR_THREAD_POOL);
                        }
                    }
                }
            } catch (InterruptedException e) {
                // (Re-)Cancel if current thread also interrupted
                threadPool.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("[INFO] " + INFO_SHUTDOWN_COMPLETE);
        if (logger != null) {
            logger.log(Logger.Level.INFO, "MainServer", INFO_SHUTDOWN_COMPLETE);
        }
    }

    @Deprecated
    public static int getActiveConnections() {
        return activeConnections.get();
    }
}