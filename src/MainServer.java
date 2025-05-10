import config.Config;
import constants.Constants;
import database.Database;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import logs.Logger;
import network.ClientHandler;
import utils.IOProcessor;
public class MainServer {
    private static ServerSocket serverSocket;
    private static ExecutorService threadPool;
    private static final Logger logger = Logger.getInstance();
    private static final AtomicInteger aliveConnection = new AtomicInteger(0);

    private static volatile boolean running = true;

    public static void main(String[] args) {
        try {
            initializeServer();
            startListener();
        } catch (IOException e) {
            int port = Config.getServerPort();
            logger.log(Logger.Level.FATAL, "File Sharing System Server", Constants.ErrorMessages.ERR_START_SERVER + " " + port, e);
            System.exit(1);
        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "File Sharing System Server", "Unexpected error during server startup", e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }

    private static void initializeServer() throws IOException {
        int port = Config.getServerPort();
        int maxThreads = Config.getMaxThreads();
        int threadCount = Config.getThreadCount();

        try {
            Database.initializeDatabaseSchema();
        } catch (Exception e) {
            logger.log(Logger.Level.FATAL, "File Sharing System Server", Constants.ErrorMessages.ERR_DATABASE_SCHEMA, e);
            throw e;
        }

        initializeThreadPool(threadCount, maxThreads);
        logger.log(Logger.Level.INFO, "File Sharing System Server",
                String.format(Constants.InfoMessages.INFO_SERVER_STARTING, port, threadCount, maxThreads));
        Runtime.getRuntime().addShutdownHook(new Thread(MainServer::shutdown));

        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(Config.getSocketTimeout() / 10);
        logger.log(Logger.Level.INFO, "File Sharing System Server", Constants.InfoMessages.INFO_SERVER_STARTED);
    }

    private static void initializeThreadPool(int threadCount, int maxThreads) {
        RejectedExecutionHandler retry = (r, executor) -> {
            try {
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Logger.Level.ERROR, "File Sharing System Server",
                        Constants.ErrorMessages.ERR_THREAD_INTERRUPTED);
            }
        };

        threadPool = new ThreadPoolExecutor(
            threadCount,
            maxThreads,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            Executors.defaultThreadFactory(),
            retry
        );
    }

    private static void startListener() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                int existingConnection = aliveConnection.incrementAndGet();
                logger.log(Logger.Level.INFO, "File Sharing System Server",
                        String.format(Constants.InfoMessages.INFO_NEW_CONNECTION,
                                clientSocket.getRemoteSocketAddress(), existingConnection));

                threadPool.execute(() -> {
                    try {
                        new ClientHandler(clientSocket).run();
                    } finally {
                        int remaining = aliveConnection.decrementAndGet();
                        logger.log(Logger.Level.INFO, "File Sharing System Server",
                                String.format(Constants.InfoMessages.INFO_CONNECTION_CLOSED, remaining));
                    }
                });
            } catch (SocketTimeoutException e) {
                // Timeout handler
            } catch (IOException e) {
                if (running) {
                    logger.log(Logger.Level.ERROR, "File Sharing System Server", Constants.ErrorMessages.ERR_ACCEPT_CONNECTION, e);
                }
            }
        }
    }

    private static void shutdown() {
        logger.log(Logger.Level.INFO, "File Sharing System Server", Constants.InfoMessages.INFO_SHUTDOWN);

        if (!running) {
            return;
        }
        running = false;

        shutdownServer();
        shutdownThreadPool();

        logger.log(Logger.Level.INFO, "File Sharing System Server", Constants.InfoMessages.INFO_SHUTDOWN_COMPLETE);
    }

    private static void shutdownServer() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            IOProcessor.closeCheck(serverSocket, "server socket", logger);
        }
    }

    private static void shutdownThreadPool() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                    if (!threadPool.awaitTermination(15, TimeUnit.SECONDS)) {
                        logger.log(Logger.Level.ERROR, "File Sharing System Server", Constants.ErrorMessages.ERR_THREAD_POOL);
                    }
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}