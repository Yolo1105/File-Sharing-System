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
    private static final String DELETE_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s deleted file: %s\n";

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

    private boolean isUtilityConnection(String clientName) {
        return Config.isUtilityConnection(clientName);
    }

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

    public void broadcast(String message) {
        broadcast(message, null); // Broadcast to all clients
    }

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

    public void broadcastFileUpload(String uploaderName, String filename) {
        // Skip notifications for utility connections
        if (isUtilityConnection(uploaderName)) {
            return;
        }

        broadcast(String.format(UPLOAD_MESSAGE_FORMAT, uploaderName, filename), null);
    }

    public void broadcastFileDownload(String downloaderName, String filename) {
        // Skip notifications for utility connections
        if (isUtilityConnection(downloaderName)) {
            return;
        }

        broadcast(String.format(DOWNLOAD_MESSAGE_FORMAT, downloaderName, filename), null);
    }

    public void broadcastFileDeletion(String deleterName, String filename) {
        // Skip notifications for utility connections
        if (isUtilityConnection(deleterName)) {
            return;
        }

        broadcast(String.format(DELETE_MESSAGE_FORMAT, deleterName, filename), null);
    }

    public int getConnectedClientsCount() {
        int count = 0;
        for (String key : clientWriters.keySet()) {
            if (!isUtilityConnection(key)) {
                count++;
            }
        }
        return count;
    }

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

    public boolean isClientConnected(String clientName) {
        return clientWriters.containsKey(clientName);
    }
}