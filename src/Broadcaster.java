import java.io.BufferedWriter;
import java.io.IOException;
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

    public void register(String clientName, BufferedWriter writer) {
        clientWriters.put(clientName, writer);
        logger.log(Logger.Level.INFO, "Broadcaster", "Registered client: " + clientName);

        // Notify other clients about the new user
        broadcast("[SERVER] " + clientName + " has joined the server.", clientName);
    }

    public void broadcast(String message) {
        broadcast(message, null); // Broadcast to all clients
    }

    /**
     * Broadcasts a message to all clients except the specified client (if not null)
     * @param message The message to broadcast
     * @param excludeClient Client to exclude from broadcast (or null to broadcast to all)
     */
    public void broadcast(String message, String excludeClient) {
        // Create a copy of entries to avoid concurrent modification issues
        for (Map.Entry<String, BufferedWriter> entry : clientWriters.entrySet()) {
            // Skip the excluded client if specified
            if (excludeClient != null && excludeClient.equals(entry.getKey())) {
                continue;
            }

            try {
                entry.getValue().write(message + "\n");
                entry.getValue().flush();
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "Broadcaster", "Failed to message " + entry.getKey(), e);
                // Mark for removal on next iteration
                clientWriters.remove(entry.getKey());
            }
        }
        logger.log(Logger.Level.INFO, "Broadcaster", "Broadcast message: " + message +
                (excludeClient != null ? " (excluding " + excludeClient + ")" : ""));
    }
}