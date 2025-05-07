import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainServer {
    private static final Logger logger = Logger.getInstance();

    public static void main(String[] args) {
        int port = Config.getServerPort();
        int maxThreads = Config.getMaxThreads();

        ExecutorService pool = Executors.newFixedThreadPool(maxThreads);

        logger.log(Logger.Level.INFO, "MainServer", "Starting server on port " + port + " with " + maxThreads + " threads");

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.log(Logger.Level.INFO, "MainServer", "Server shutting down...");
            pool.shutdown();
        }));

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.log(Logger.Level.INFO, "MainServer", "Server started successfully");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    logger.log(Logger.Level.INFO, "MainServer", "New connection accepted from: " + clientSocket.getRemoteSocketAddress());
                    pool.execute(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    logger.log(Logger.Level.ERROR, "MainServer", "Error accepting client connection", e);
                }
            }
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "MainServer", "Could not start server on port " + port, e);
            System.exit(1);
        }
    }
}