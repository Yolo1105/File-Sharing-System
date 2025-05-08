import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Broadcaster {
    private static final Broadcaster instance = new Broadcaster();
    private static final Logger logger = Logger.getInstance();

    // Maps client name to their writer stream
    private final Map<String, BufferedWriter> clientWriters = new ConcurrentHashMap<>();

    private Broadcaster() {}

    public static Broadcaster getInstance() {
        return instance;
    }

    /**
     * Registers a new client and notifies other clients
     */
    public void register(String clientName, BufferedWriter writer) {
        // Don't register clients with special suffixes (_upload, _download, _verify)
        if (clientName.contains("_upload") || clientName.contains("_download") || clientName.contains("_verify")) {
            logger.log(Logger.Level.INFO, "Broadcaster", "Skipping notification for utility connection: " + clientName);
            clientWriters.put(clientName, writer);
            return;
        }

        clientWriters.put(clientName, writer);
        logger.log(Logger.Level.INFO, "Broadcaster", "Registered client: " + clientName);

        // Notify other clients about the new user with a more visible notification
        broadcast("\n@SERVER_NOTIFICATION:" + clientName + " has joined the server.\n", clientName);
    }

    /**
     * Unregisters a client when they disconnect
     */
    public void unregister(String clientName) {
        // Don't notify about utility connections
        if (clientName.contains("_upload") || clientName.contains("_download") || clientName.contains("_verify")) {
            clientWriters.remove(clientName);
            return;
        }

        if (clientWriters.remove(clientName) != null) {
            logger.log(Logger.Level.INFO, "Broadcaster", "Unregistered client: " + clientName);

            // Notify other clients that a user has left
            broadcast("\n@SERVER_NOTIFICATION:" + clientName + " has left the server.\n", null);
        }
    }

    /**
     * Broadcasts a message to all clients
     */
    public void broadcast(String message) {
        broadcast(message, null); // Broadcast to all clients
    }

    /**
     * Broadcasts a message to all clients except the specified client (if not null)
     * @param message The message to broadcast
     * @param excludeClient Client to exclude from broadcast (or null to broadcast to all)
     */
    public void broadcast(String message, String excludeClient) {
        int successCount = 0;
        int failureCount = 0;

        // Create a copy of entries to avoid concurrent modification issues
        for (Map.Entry<String, BufferedWriter> entry : clientWriters.entrySet()) {
            // Skip utility connections and the excluded client
            if (entry.getKey().contains("_upload") || entry.getKey().contains("_download") ||
                    entry.getKey().contains("_verify") ||
                    (excludeClient != null && excludeClient.equals(entry.getKey()))) {
                continue;
            }

            try {
                entry.getValue().write(message);
                entry.getValue().flush();
                successCount++;
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "Broadcaster", "Failed to send message to " + entry.getKey() + ": " + e.getMessage());
                // Remove the client with failed connection
                clientWriters.remove(entry.getKey());
                failureCount++;
            }
        }

        logger.log(Logger.Level.INFO, "Broadcaster",
                "Broadcast message: " + message.trim() +
                        (excludeClient != null ? " (excluding " + excludeClient + ")" : "") +
                        " to " + successCount + " clients" +
                        (failureCount > 0 ? ", " + failureCount + " failed deliveries" : ""));
    }

    /**
     * Broadcasts a file upload notification
     * @param uploaderName The name of the client who uploaded the file
     * @param filename The name of the uploaded file
     */
    public void broadcastFileUpload(String uploaderName, String filename) {
        // Skip notifications for utility connections
        if (uploaderName.contains("_upload") || uploaderName.contains("_download") || uploaderName.contains("_verify")) {
            return;
        }

        broadcast("\n@SERVER_NOTIFICATION:" + uploaderName + " uploaded file: " + filename + "\n", null);
    }

    /**
     * Broadcasts a file download notification
     * @param downloaderName The name of the client who downloaded the file
     * @param filename The name of the downloaded file
     */
    public void broadcastFileDownload(String downloaderName, String filename) {
        // Skip notifications for utility connections
        if (downloaderName.contains("_upload") || downloaderName.contains("_download") || downloaderName.contains("_verify")) {
            return;
        }

        broadcast("\n@SERVER_NOTIFICATION:" + downloaderName + " downloaded file: " + filename + "\n", null);
    }

    /**
     * Gets the number of connected clients
     * @return Number of connected clients
     */
    public int getConnectedClientsCount() {
        return clientWriters.size();
    }
}