import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringTokenizer;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();
    private static final Broadcaster broadcaster = Broadcaster.getInstance();
    private static final Logger logger = Logger.getInstance();
    private String clientName = "Unknown";
    private BufferedReader reader;
    private BufferedWriter writer;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private volatile boolean running = true;
    private static final int BUFFER_SIZE = 32768; // Increased buffer size for better performance
    private static final int SOCKET_TIMEOUT = 120000; // 2 minute timeout

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        logger.log(Logger.Level.INFO, "ClientHandler", "New client connected: " + socket.getRemoteSocketAddress());
        try {
            // Configure socket for better stability
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT); // 2 minute timeout

            // Create buffered streams for better performance
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
            dataOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));

            writer.write("Welcome to the File Server. Please identify using CLIENT_ID <name>\n");
            writer.flush();

            String initial = reader.readLine();

            if (initial != null && initial.startsWith("CLIENT_ID")) {
                String[] parts = initial.split("\\s+", 2);
                if (parts.length == 2) {
                    clientName = parts[1].trim();

                    // Don't broadcast notifications for utility connections (with _upload, _download, etc.)
                    if (!clientName.contains("_upload") && !clientName.contains("_download") && !clientName.contains("_verify")) {
                        broadcaster.register(clientName, writer);
                    }
                } else {
                    writer.write("Missing client name. Connection closed.\n");
                    writer.flush();
                    return;
                }
            } else {
                writer.write("Missing CLIENT_ID. Connection closed.\n");
                writer.flush();
                return;
            }

            writer.write("You can now use: UPLOAD <filename>, DOWNLOAD <filename>, LIST\n");
            writer.flush();

            String line;
            while (running && (line = reader.readLine()) != null) {
                logger.log(Logger.Level.INFO, "ClientHandler", clientName + " issued command: " + line);
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) continue;

                String command = tokenizer.nextToken().toUpperCase();

                try {
                    switch (command) {
                        case "UPLOAD":
                            if (!tokenizer.hasMoreTokens()) {
                                writer.write("Missing filename for UPLOAD.\n");
                                writer.flush();
                                break;
                            }

                            String uploadFilename = tokenizer.nextToken();

                            if (!uploadFilename.toLowerCase().endsWith(".txt")) {
                                writer.write("ERROR: Only .txt files are allowed.\n");
                                writer.flush();
                                break;
                            }

                            try {
                                // Increase timeout for file operations
                                socket.setSoTimeout(SOCKET_TIMEOUT * 2);

                                // Handle upload with clear protocol boundaries
                                fileManager.receiveFile(uploadFilename, dataInputStream);

                                // Log the successful upload
                                DBLogger.log(clientName, "UPLOAD", uploadFilename);

                                // Add a small delay to ensure streams are synchronized
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }

                                // Make sure all data is flushed
                                dataOutputStream.flush();

                                // Send explicit success response
                                String successMessage = "Upload successful to server_files/.\n";
                                writer.write(successMessage);
                                writer.flush();

                                // Only broadcast notifications for regular clients, not special connections
                                String baseClientName = clientName;
                                if (clientName.contains("_")) {
                                    baseClientName = clientName.substring(0, clientName.indexOf("_"));
                                }

                                // Use the improved broadcaster method for file upload notifications
                                if (!clientName.contains("_upload") && !clientName.contains("_download") && !clientName.contains("_verify")) {
                                    broadcaster.broadcastFileUpload(baseClientName, uploadFilename);
                                }

                                // Reset timeout to normal
                                socket.setSoTimeout(SOCKET_TIMEOUT);
                            } catch (RuntimeException e) {
                                logger.log(Logger.Level.ERROR, "ClientHandler", "Upload failed: " + e.getMessage(), e);
                                writer.write("ERROR: Upload failed: " + e.getMessage() + "\n");
                                writer.flush();

                                // Reset timeout to normal
                                socket.setSoTimeout(SOCKET_TIMEOUT);
                            }
                            break;

                        case "DOWNLOAD":
                            if (!tokenizer.hasMoreTokens()) {
                                writer.write("ERROR: Please enter a filename to download\n");
                                writer.flush();
                                break;
                            }

                            String downloadFilename = tokenizer.nextToken();

                            File requestedFile = new File(FileManager.SHARED_DIR + downloadFilename);

                            if (!requestedFile.exists() || !requestedFile.isFile()) {
                                writer.write("ERROR: File '" + downloadFilename + "' not found on server.\n");
                                writer.flush();

                                // Send -1 as file size to indicate file not found
                                dataOutputStream.writeLong(-1);
                                dataOutputStream.flush();
                                break;
                            }

                            try {
                                // Increase timeout during file transfer
                                socket.setSoTimeout(SOCKET_TIMEOUT * 2);

                                // Send the file
                                fileManager.sendFile(downloadFilename, socket.getOutputStream());

                                // Log the download
                                DBLogger.log(clientName, "DOWNLOAD", downloadFilename);

                                // Small delay to ensure protocol sync
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }

                                writer.write("Download completed successfully\n");
                                writer.flush();

                                // Only broadcast notifications for regular clients, not special connections
                                String baseClientName = clientName;
                                if (clientName.contains("_")) {
                                    baseClientName = clientName.substring(0, clientName.indexOf("_"));
                                }

                                // Use the improved broadcaster method for file download notifications
                                if (!clientName.contains("_upload") && !clientName.contains("_download") && !clientName.contains("_verify")) {
                                    broadcaster.broadcastFileDownload(baseClientName, downloadFilename);
                                }

                                // Reset timeout to normal
                                socket.setSoTimeout(SOCKET_TIMEOUT);
                            } catch (RuntimeException e) {
                                logger.log(Logger.Level.ERROR, "ClientHandler", "Error sending file: " + e.getMessage(), e);
                                writer.write("ERROR: Failed to send file: " + e.getMessage() + "\n");
                                writer.flush();

                                // Reset timeout to normal
                                socket.setSoTimeout(SOCKET_TIMEOUT);
                            }
                            break;

                        case "LIST":
                            String fileList = fileManager.listFiles();

                            // Add a clear end marker to help client know when response is complete
                            if (fileList.contains("No files available")) {
                                writer.write("Available files:\nNo files available on the server.\n*END*\n");
                            } else {
                                // Send the file list with a clear end marker
                                writer.write(fileList.trim() + "\n*END*\n");
                            }
                            writer.flush();
                            break;

                        default:
                            writer.write("Unknown command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST\n");
                            writer.flush();
                            break;
                    }
                } catch (Exception e) {
                    logger.log(Logger.Level.ERROR, "ClientHandler", "Error processing command: " + command, e);
                    try {
                        writer.write("ERROR: Command execution failed: " + e.getMessage() + "\n");
                        writer.flush();
                    } catch (IOException ioe) {
                        // Connection probably lost, breaking out of the loop
                        logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to send error message, connection may be lost", ioe);
                        break;
                    }
                }
            }

        } catch (SocketException e) {
            logger.log(Logger.Level.INFO, "ClientHandler", "Connection closed by client: " + clientName);
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection lost with " + clientName, e);
        } finally {
            // Clean up resources
            closeResources();

            // Only unregister regular clients, not special connections
            if (clientName != null && !clientName.isEmpty() &&
                    !clientName.contains("_upload") && !clientName.contains("_download") && !clientName.contains("_verify")) {
                broadcaster.unregister(clientName);
            }
        }
    }

    private void closeResources() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataInputStream != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
            if (socket != null && !socket.isClosed()) socket.close();

            logger.log(Logger.Level.INFO, "ClientHandler", "All resources cleaned up for client: " + clientName);
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Error closing resources", e);
        }
    }
}