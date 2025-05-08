package network;

import logs.Logger;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

import config.Config;

public class Broadcaster {
    private static final Broadcaster instance = new Broadcaster();
    private static final Logger logger = Logger.getInstance();

    // Maps client name to their writer stream
    private final Map<String, BufferedWriter> clientWriters = new ConcurrentHashMap<>();

    // Message prefixes and formats
    private static final String NOTIFICATION_PREFIX = Config.Protocol.NOTIFICATION_PREFIX;
    private static final String JOIN_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s has joined the server.\n";
    private static final String LEAVE_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s has left the server.\n";
    private static final String UPLOAD_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s uploaded file: %s\n";
    private static final String DOWNLOAD_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s downloaded file: %s\n";

    // Log messages
    private static final String LOG_SKIPPING_NOTIFICATION = "Skipping notification for utility connection: %s";
    private static final String LOG_REGISTERED = "Registered client: %s";
    private static final String LOG_UNREGISTERED = "Unregistered client: %s";
    private static final String LOG_BROADCAST_RESULT = "Broadcast message: %s (excluding %s) to %d clients%s";
    private static final String LOG_SEND_FAILED = "Failed to send message to %s: %s";

    private Broadcaster() {}

    public static Broadcaster getInstance() {
        return instance;
    }

    /**
     * Checks if the client is a utility connection (e.g., _upload, _download)
     * Helper method to consolidate repeated checks
     * @param clientName The client name to check
     * @return true if the client is a utility connection, false otherwise
     */
    private boolean isUtilityConnection(String clientName) {
        return Config.isUtilityConnection(clientName);
    }

    /**
     * Registers a new client and notifies other clients
     * @param clientName The name of the client to register
     * @param writer The writer stream for the client
     */
    public void register(String clientName, BufferedWriter writer) {
        // Don't register clients with special suffixes (_upload, _download, _verify)
        if (isUtilityConnection(clientName)) {
            logger.log(Logger.Level.INFO, "Broadcaster",
                    String.format(LOG_SKIPPING_NOTIFICATION, clientName));
            clientWriters.put(clientName, writer);
            return;
        }

        clientWriters.put(clientName, writer);
        logger.log(Logger.Level.INFO, "Broadcaster", String.format(LOG_REGISTERED, clientName));

        // Notify other clients about the new user with a clear notification format
        broadcast(String.format(JOIN_MESSAGE_FORMAT, clientName), clientName);
    }

    /**
     * Unregisters a client when they disconnect
     * @param clientName The name of the client to unregister
     */
    public void unregister(String clientName) {
        // Don't notify about utility connections
        if (isUtilityConnection(clientName)) {
            clientWriters.remove(clientName);
            return;
        }

        if (clientWriters.remove(clientName) != null) {
            logger.log(Logger.Level.INFO, "Broadcaster", String.format(LOG_UNREGISTERED, clientName));

            // Notify other clients that a user has left
            broadcast(String.format(LEAVE_MESSAGE_FORMAT, clientName), null);
        }
    }

    /**
     * Broadcasts a message to all clients
     * @param message The message to broadcast
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

        // Get a snapshot of clients to avoid concurrent modification issues
        List<Map.Entry<String, BufferedWriter>> clients = new ArrayList<>(clientWriters.entrySet());

        for (Map.Entry<String, BufferedWriter> entry : clients) {
            String clientName = entry.getKey();
            BufferedWriter writer = entry.getValue();

            // Skip utility connections and the excluded client
            if (isUtilityConnection(clientName) ||
                    (excludeClient != null && excludeClient.equals(clientName))) {
                continue;
            }

            try {
                writer.write(message);
                writer.flush();
                successCount++;
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "Broadcaster",
                        String.format(LOG_SEND_FAILED, clientName, e.getMessage()));
                // Remove the client with failed connection
                clientWriters.remove(clientName);
                failureCount++;
            }
        }

        String failureInfo = failureCount > 0 ? ", " + failureCount + " failed deliveries" : "";

        logger.log(Logger.Level.INFO, "Broadcaster",
                String.format(LOG_BROADCAST_RESULT,
                        message.trim(),
                        excludeClient != null ? excludeClient : "none",
                        successCount,
                        failureInfo));
    }

    /**
     * Broadcasts a file upload notification
     * @param uploaderName The name of the client who uploaded the file
     * @param filename The name of the uploaded file
     */
    public void broadcastFileUpload(String uploaderName, String filename) {
        // Skip notifications for utility connections
        if (isUtilityConnection(uploaderName)) {
            return;
        }

        broadcast(String.format(UPLOAD_MESSAGE_FORMAT, uploaderName, filename), null);
    }

    /**
     * Broadcasts a file download notification
     * @param downloaderName The name of the client who downloaded the file
     * @param filename The name of the downloaded file
     */
    public void broadcastFileDownload(String downloaderName, String filename) {
        // Skip notifications for utility connections
        if (isUtilityConnection(downloaderName)) {
            return;
        }

        broadcast(String.format(DOWNLOAD_MESSAGE_FORMAT, downloaderName, filename), null);
    }

    /**
     * Gets the number of connected clients
     * @return Number of connected clients (excluding utility connections)
     */
    public int getConnectedClientsCount() {
        int count = 0;
        for (String key : clientWriters.keySet()) {
            if (!isUtilityConnection(key)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sends a direct message to a specific client
     * @param clientName The name of the client to message
     * @param message The message to send
     * @return true if message was sent, false otherwise
     */
    public boolean sendDirectMessage(String clientName, String message) {
        BufferedWriter writer = clientWriters.get(clientName);
        if (writer != null) {
            try {
                writer.write(message);
                writer.flush();
                return true;
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "Broadcaster",
                        String.format(LOG_SEND_FAILED, clientName, e.getMessage()));
                clientWriters.remove(clientName);
            }
        }
        return false;
    }

    /**
     * Checks if a client is currently connected
     * @param clientName The name of the client to check
     * @return true if client is connected, false otherwise
     */
    public boolean isClientConnected(String clientName) {
        return clientWriters.containsKey(clientName);
    }
}