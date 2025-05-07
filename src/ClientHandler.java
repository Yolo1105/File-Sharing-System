import java.io.*;
import java.net.Socket;
import java.util.StringTokenizer;
import java.io.File;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();
    private static final Broadcaster broadcaster = Broadcaster.getInstance();
    private static final Logger logger = Logger.getInstance();
    private String clientName = "Unknown";

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        logger.log(Logger.Level.INFO, "ClientHandler", "New client connected: " + socket.getRemoteSocketAddress());
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
                logger.log(Logger.Level.INFO, "ClientHandler", "Client identified as: " + clientName);
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
                logger.log(Logger.Level.INFO, "ClientHandler", clientName + " issued command: " + line);
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

                        // Check if file exists before attempting to send
                        File requestedFile = new File(FileManager.SHARED_DIR + downloadFilename);
                        if (!requestedFile.exists() || !requestedFile.isFile()) {
                            writer.write("ERROR: File '" + downloadFilename + "' not found on server.\n");
                            writer.flush();
                            logger.log(Logger.Level.WARNING, "ClientHandler", "Download failed - file not found: " + downloadFilename);
                            break;
                        }

                        try {
                            // Send file data
                            fileManager.sendFile(downloadFilename, socket.getOutputStream());
                            // Log the action
                            DBLogger.log(clientName, "DOWNLOAD", downloadFilename);
                            // Wait a bit to ensure all data is flushed before sending text response
                            Thread.sleep(100);
                            // Send text response
                            writer.write("Download complete.\n");
                            writer.flush();
                            broadcaster.broadcast("[DOWNLOAD] " + clientName + " downloaded: " + downloadFilename);
                        } catch (InterruptedException ex) {
                            logger.log(Logger.Level.ERROR, "ClientHandler", "Interrupted during download", ex);
                        }
                        break;

                    case "LIST":
                        String fileList = fileManager.listFiles();
                        writer.write(fileList);
                        writer.flush();
                        logger.log(Logger.Level.INFO, "ClientHandler", clientName + " requested file list");
                        break;

                    case "EXIT":
                        broadcaster.broadcast("[SERVER] " + clientName + " has left the server.");
                        broadcaster.deregister(clientName);
                        logger.log(Logger.Level.INFO, "ClientHandler", "Client disconnected: " + clientName);
                        return;

                    default:
                        writer.write("Unknown command. Available commands: UPLOAD, DOWNLOAD, LIST, EXIT\n");
                        writer.flush();
                        break;
                }
            }

        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection lost with " + clientName, e);
            broadcaster.deregister(clientName);
        }
    }
}