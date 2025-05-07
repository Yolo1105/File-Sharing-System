import java.io.*;
import java.net.Socket;
import java.util.StringTokenizer;
import java.io.File;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();
    private static final Logger logger = Logger.getInstance();
    private String clientName = "Unknown";

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        logger.log(Logger.Level.INFO, "ClientHandler", "New client connected: " + socket.getRemoteSocketAddress());

        try (
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())
        ) {
            dataOut.writeUTF("Welcome to the File Server. Please identify using CLIENT_ID <name>");
            dataOut.flush();

            String initial = dataIn.readUTF();
            if (initial.startsWith("CLIENT_ID")) {
                String[] parts = initial.split("\\s+", 2);
                if (parts.length == 2) {
                    clientName = parts[1].trim();
                }
                logger.log(Logger.Level.INFO, "ClientHandler", "Client identified as: " + clientName);
            } else {
                dataOut.writeUTF("Missing CLIENT_ID. Connection closed.");
                return;
            }

            dataOut.writeUTF("You can now use: UPLOAD <filename>, DOWNLOAD <filename>, LIST, EXIT");

            while (true) {
                String line = dataIn.readUTF();
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) continue;

                String command = tokenizer.nextToken().toUpperCase();

                switch (command) {
                    case "UPLOAD": {
                        if (!tokenizer.hasMoreTokens()) {
                            dataOut.writeUTF("Missing filename for UPLOAD.");
                            break;
                        }
                        String filename = tokenizer.nextToken();
                        fileManager.receiveFile(filename, dataIn);
                        DBLogger.log(clientName, "UPLOAD", filename);
                        dataOut.writeUTF("Upload successful: " + filename);
                        break;
                    }

                    case "DOWNLOAD": {
                        if (!tokenizer.hasMoreTokens()) {
                            dataOut.writeUTF("Missing filename for DOWNLOAD.");
                            break;
                        }
                        String filename = tokenizer.nextToken();
                        File file = new File(FileManager.SHARED_DIR + filename);
                        if (!file.exists()) {
                            dataOut.writeLong(-1); // flag for not found
                            dataOut.writeUTF("File not found: " + filename);
                            break;
                        }

                        fileManager.sendFile(filename, dataOut);
                        DBLogger.log(clientName, "DOWNLOAD", filename);
                        dataOut.writeUTF("Download complete: " + filename);
                        break;
                    }

                    case "LIST": {
                        String list = fileManager.listFiles();
                        dataOut.writeUTF(list);
                        break;
                    }

                    case "EXIT":
                        dataOut.writeUTF("Goodbye!");
                        return;

                    default:
                        dataOut.writeUTF("Unknown command.");
                        break;
                }
            }

        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection error", e);
        }
    }
}
