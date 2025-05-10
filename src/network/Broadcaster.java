package network;

import config.Config;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import logs.Logger;

public class Broadcaster {
    private static final Broadcaster instance = new Broadcaster();
    private static final Logger logger = Logger.getInstance();
    private final Map<String, BufferedWriter> clientWriters = new ConcurrentHashMap<>();

    private static final String NOTIFICATION_PREFIX = Config.Protocol.NOTIFICATION_PREFIX;
    private static final String JOIN_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s has joined the server.\n";
    private static final String LEAVE_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s has left the server.\n";
    private static final String UPLOAD_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s uploaded file: %s\n";
    private static final String DOWNLOAD_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s downloaded file: %s\n";
    private static final String DELETE_MESSAGE_FORMAT = NOTIFICATION_PREFIX + " %s deleted file: %s\n";

    private static final String LOG_SKIPPING_NOTIFICATION = "Skipping notification for utility connection: %s";
    private static final String LOG_REGISTERED = "Registered client: %s";
    private static final String LOG_UNREGISTERED = "Unregistered client: %s";
    private static final String LOG_BROADCAST_RESULT = "Broadcast message: %s (excluding %s) to %d clients%s";
    private static final String LOG_SEND_FAILED = "Failed to send message to %s: %s";

    private Broadcaster() {}
    public static Broadcaster getInstance() {
        return instance;
    }

    private boolean ServiceConnectionCheck(String clientName) {
        return Config.ServiceConnectionCheck(clientName);
    }

    public void register(String clientName, BufferedWriter writer) {
        if (ServiceConnectionCheck(clientName)) {
            logger.log(Logger.Level.INFO, "Broadcaster",
                    String.format(LOG_SKIPPING_NOTIFICATION, clientName));
            clientWriters.put(clientName, writer);
            return;
        }

        clientWriters.put(clientName, writer);
        logger.log(Logger.Level.INFO, "Broadcaster", String.format(LOG_REGISTERED, clientName));
        broadcast(String.format(JOIN_MESSAGE_FORMAT, clientName), clientName);
    }

    public void unregister(String clientName) {
        if (ServiceConnectionCheck(clientName)) {
            clientWriters.remove(clientName);
            return;
        }

        if (clientWriters.remove(clientName) != null) {
            logger.log(Logger.Level.INFO, "Broadcaster", String.format(LOG_UNREGISTERED, clientName));
            broadcast(String.format(LEAVE_MESSAGE_FORMAT, clientName), null);
        }
    }

    public void broadcast(String message, String excludeClient) {
        int successCount = 0;
        int failureCount = 0;

        List<Map.Entry<String, BufferedWriter>> clients = new ArrayList<>(clientWriters.entrySet());

        for (Map.Entry<String, BufferedWriter> entry : clients) {
            String clientName = entry.getKey();
            BufferedWriter writer = entry.getValue();

            if (ServiceConnectionCheck(clientName) ||
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
        if (ServiceConnectionCheck(uploaderName)) {
            return;
        }
        broadcast(String.format(UPLOAD_MESSAGE_FORMAT, uploaderName, filename), null);
    }

    public void broadcastFileDownload(String downloaderName, String filename) {
        if (ServiceConnectionCheck(downloaderName)) {
            return;
        }
        broadcast(String.format(DOWNLOAD_MESSAGE_FORMAT, downloaderName, filename), null);
    }

    public void broadcastFileDeletion(String deleterName, String filename) {
        if (ServiceConnectionCheck(deleterName)) {
            return;
        }
        broadcast(String.format(DELETE_MESSAGE_FORMAT, deleterName, filename), null);
    }
}