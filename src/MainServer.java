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
import constants.Constants;
import database.Database;
import utils.IOUtils;

public class MainServer {
    private static final Logger logger = Logger.getInstance();
    private static ServerSocket serverSocket;
    private static ExecutorService threadPool;
    private static volatile boolean running = true;

    // Track number of active connections
    private static final AtomicInteger activeConnections = new AtomicInteger(0);

    public static void main(String[] args) {
        try {
            initializeServer();
            startServerLoop();
        } catch (IOException e) {
            int port = Config.getServerPort();
            logger.log(Logger.Level.FATAL, "MainServer", Constants.ErrorMessages.ERR_START_SERVER + " " + port, e);
            System.exit(1);
        } catch (Exception e) {
            // Catch any other startup errors
            logger.log(Logger.Level.FATAL, "MainServer", "Unexpected error during server startup", e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    // Extracted method for server initialization to reduce complexity in main method
    private static void initializeServer() throws IOException {
        // Initialize the logging system first
        if (logger != null) {
            logger.log(Logger.Level.INFO, "MainServer", Constants.InfoMessages.INFO_STARTING.formatted("server"));
        }

        int port = Config.getServerPort();
        int maxThreads = Config.getMaxThreads();
        int corePoolSize = Config.getCorePoolSize();

        // Initialize database schema through the connection pool
        try {
            Database.initializeDatabaseSchema();
        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "MainServer", Constants.ErrorMessages.ERR_DATABASE_SCHEMA, e);
            throw e; // Re-throw to be caught by main
        }

        // Create improved thread pool with wait policy
        initializeThreadPool(corePoolSize, maxThreads);

        logger.log(Logger.Level.INFO, "MainServer",
                String.format(Constants.InfoMessages.INFO_SERVER_STARTING, port, corePoolSize, maxThreads));

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(MainServer::shutdown));

        // Create the server socket and start accepting connections
        serverSocket = new ServerSocket(port);
        // Set a timeout so we can check if we need to shut down
        serverSocket.setSoTimeout(Config.getSocketTimeout() / 10); // 10% of socket timeout

        logger.log(Logger.Level.INFO, "MainServer", Constants.InfoMessages.INFO_SERVER_STARTED);
    }

    // Extracted method to initialize thread pool
    private static void initializeThreadPool(int corePoolSize, int maxThreads) {
        // Custom wait policy instead of CallerRunsPolicy
        RejectedExecutionHandler waitPolicy = (r, executor) -> {
            try {
                // Wait for space in the queue rather than rejecting outright
                // This prevents the main thread from executing the task directly
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Logger.Level.ERROR, "MainServer",
                        Constants.ErrorMessages.ERR_THREAD_INTERRUPTED);
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
    }

    // Extracted server loop to separate method
    private static void startServerLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();

                // Track connection and submit to thread pool
                int currentConnections = activeConnections.incrementAndGet();
                logger.log(Logger.Level.INFO, "MainServer",
                        String.format(Constants.InfoMessages.INFO_NEW_CONNECTION,
                                clientSocket.getRemoteSocketAddress(), currentConnections));

                threadPool.execute(() -> {
                    try {
                        new ClientHandler(clientSocket).run();
                    } finally {
                        // Decrement connection count when client handler finishes
                        int remaining = activeConnections.decrementAndGet();
                        logger.log(Logger.Level.INFO, "MainServer",
                                String.format(Constants.InfoMessages.INFO_CONNECTION_CLOSED, remaining));
                    }
                });
            } catch (SocketTimeoutException e) {
                // This is normal - it allows us to periodically check if we should shut down
            } catch (IOException e) {
                if (running) {
                    logger.log(Logger.Level.ERROR, "MainServer", Constants.ErrorMessages.ERR_ACCEPT_CONNECTION, e);
                }
            }
        }
    }

    private static void shutdown() {
        logger.log(Logger.Level.INFO, "MainServer", Constants.InfoMessages.INFO_SHUTDOWN);

        // Prevent multiple shutdown attempts
        if (!running) {
            return;
        }

        // Mark as not running
        running = false;

        shutdownServerSocket();
        shutdownThreadPool();

        logger.log(Logger.Level.INFO, "MainServer", Constants.InfoMessages.INFO_SHUTDOWN_COMPLETE);
    }

    // Extracted server socket shutdown to separate method
    private static void shutdownServerSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            IOUtils.safeClose(serverSocket, "server socket", logger);
        }
    }

    // Extracted thread pool shutdown to separate method
    private static void shutdownThreadPool() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                // Wait for existing tasks to terminate
                if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    // Force shutdown if still executing after timeout
                    threadPool.shutdownNow();
                    if (!threadPool.awaitTermination(15, TimeUnit.SECONDS)) {
                        logger.log(Logger.Level.ERROR, "MainServer", Constants.ErrorMessages.ERR_THREAD_POOL);
                    }
                }
            } catch (InterruptedException e) {
                // (Re-)Cancel if current thread also interrupted
                threadPool.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}