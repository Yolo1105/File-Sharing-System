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
    }

    public void deregister(String clientName) {
        clientWriters.remove(clientName);
        logger.log(Logger.Level.INFO, "Broadcaster", "Deregistered client: " + clientName);
    }

    public void broadcast(String message) {
        // Create a copy of entries to avoid concurrent modification issues
        for (Map.Entry<String, BufferedWriter> entry : clientWriters.entrySet()) {
            try {
                entry.getValue().write(message + "\n");
                entry.getValue().flush();
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "Broadcaster", "Failed to message " + entry.getKey(), e);
                // Mark for removal but don't remove here to avoid ConcurrentModificationException
                clientWriters.remove(entry.getKey());
            }
        }
        logger.log(Logger.Level.INFO, "Broadcaster", "Broadcast message: " + message);
    }
}