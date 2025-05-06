import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Broadcaster {
    private static final Broadcaster instance = new Broadcaster();

    // Maps client name to their writer stream
    private final Map<String, BufferedWriter> clientWriters = new ConcurrentHashMap<>();

    private Broadcaster() {}

    public static Broadcaster getInstance() {
        return instance;
    }

    public void register(String clientName, BufferedWriter writer) {
        clientWriters.put(clientName, writer);
        System.out.println("[BROADCAST] Registered client: " + clientName);
    }

    public void deregister(String clientName) {
        clientWriters.remove(clientName);
        System.out.println("[BROADCAST] Deregistered client: " + clientName);
    }

    public void broadcast(String message) {
        for (Map.Entry<String, BufferedWriter> entry : clientWriters.entrySet()) {
            try {
                entry.getValue().write(message + "\n");
                entry.getValue().flush();
            } catch (IOException e) {
                System.err.println("[BROADCAST ERROR] Failed to message " + entry.getKey());
            }
        }
        System.out.println("[BROADCAST] " + message);
    }
}
