import java.io.*;
import java.net.Socket;
import java.util.StringTokenizer;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();
    private static final Broadcaster broadcaster = Broadcaster.getInstance(); // For real-time updates
    private String clientName = "Unknown";

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        System.out.println("[INFO] New client connected: " + socket.getRemoteSocketAddress());
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))
        ) {
            writer.write("Welcome to the File Server. Please identify using CLIENT_ID <name>\n");
            writer.flush();

            String initial = reader.readLine();
            if (initial != null && initial.startsWith("CLIENT_ID")) {
                String[] parts = initial.split("\\s+", 2);
                if (parts.length == 2) {
                    clientName = parts[1].trim();
                    broadcaster.register(clientName, writer);
                }
                System.out.println("[INFO] Client identified as: " + clientName);
                broadcaster.broadcast("[SERVER] " + clientName + " has joined the server.");
            } else {
                writer.write("Missing CLIENT_ID. Connection closed.\n");
                writer.flush();
                return;
            }

            writer.write("You can now use: UPLOAD <filename>, DOWNLOAD <filename>, LIST\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[INFO][" + clientName + "] Command: " + line);
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) continue;

                String command = tokenizer.nextToken().toUpperCase();

                switch (command) {
                    case "UPLOAD":
                        if (!tokenizer.hasMoreTokens()) {
                            writer.write("Missing filename for UPLOAD.\n");
                            writer.flush();
                            break;
                        }
                        String uploadFilename = tokenizer.nextToken();
                        fileManager.receiveFile(uploadFilename, socket.getInputStream());
                        DBLogger.log(clientName, "UPLOAD", uploadFilename);
                        writer.write("Upload successful to server_files/.\n");
                        writer.flush();
                        broadcaster.broadcast("[UPLOAD] " + clientName + " uploaded: " + uploadFilename);
                        break;

                    case "DOWNLOAD":
                        if (!tokenizer.hasMoreTokens()) {
                            writer.write("Missing filename for DOWNLOAD.\n");
                            writer.flush();
                            break;
                        }
                        String downloadFilename = tokenizer.nextToken();
                        fileManager.sendFile(downloadFilename, socket.getOutputStream());
                        DBLogger.log(clientName, "DOWNLOAD", downloadFilename);
                        writer.write("Download saved to downloads/.\n");
                        writer.flush();
                        broadcaster.broadcast("[DOWNLOAD] " + clientName + " downloaded: " + downloadFilename);
                        break;

                    case "LIST":
                        writer.write(fileManager.listFiles());
                        writer.flush();
                        break;

                    case "EXIT":
                        broadcaster.deregister(clientName);
                        System.out.println("[INFO] " + clientName + " disconnected.");
                        return;

                    default:
                        writer.write("Unknown command.\n");
                        writer.flush();
                        break;
                }
            }

        } catch (IOException e) {
            System.out.println("[ERROR] Connection lost with " + clientName + ": " + socket.getRemoteSocketAddress());
            broadcaster.deregister(clientName);
        }
    }
}
