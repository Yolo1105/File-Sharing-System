import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringTokenizer;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();
    private static final Broadcaster broadcaster = Broadcaster.getInstance();
    private static final Logger logger = Logger.getInstance();

    // Client information
    private String clientName = "Unknown";

    // Stream handling
    private BufferedReader reader;
    private BufferedWriter writer;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;

    // State management
    private volatile boolean running = true;

    // Configuration values from Config
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final int SOCKET_TIMEOUT = Config.getSocketTimeout();
    private static final int FILE_TRANSFER_TIMEOUT = Config.getFileTransferTimeout();

    // Command constants
    private static final String CMD_UPLOAD = Config.Protocol.CMD_UPLOAD;
    private static final String CMD_DOWNLOAD = Config.Protocol.CMD_DOWNLOAD;
    private static final String CMD_LIST = Config.Protocol.CMD_LIST;
    private static final String CMD_LOGS = Config.Protocol.CMD_LOGS;
    private static final String CLIENT_ID_PREFIX = Config.Protocol.CLIENT_ID_PREFIX;
    private static final String RESPONSE_END_MARKER = Config.Protocol.RESPONSE_END_MARKER;

    // Error messages
    private static final String ERR_MISSING_FILENAME = "Missing filename for %s";
    private static final String ERR_ONLY_TXT = "ERROR: Only .txt files are allowed";
    private static final String ERR_UPLOAD_FAILED = "ERROR: Upload failed: %s";
    private static final String ERR_DOWNLOAD_FAILED = "ERROR: Failed to send file: %s";
    private static final String ERR_UNKNOWN_COMMAND = "Unknown command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, LOGS [count]";
    private static final String ERR_COMMAND_FAILED = "ERROR: Command execution failed: %s";

    // Success messages
    private static final String SUCCESS_UPLOAD = "Upload successful to database.";
    private static final String SUCCESS_DOWNLOAD = "Download completed successfully";

    // Static initialization
    static {
        try {
            // Make sure tables are verified once at startup
            fileManager.verifyDatabaseTables();
        } catch (Exception e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Error during static initialization", e);
        }
    }

    /**
     * Creates a new client handler for a socket connection
     * @param socket The connected client socket
     */
    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        logger.log(Logger.Level.INFO, "ClientHandler", "New client connected: " + socket.getRemoteSocketAddress());

        try {
            // Configure socket for better stability
            SocketUtils.configureStandardSocket(socket);

            // Create IO streams
            createStreams();

            // Welcome sequence
            writer.write("Welcome to the File Server. Please identify using CLIENT_ID <n>\n");
            writer.flush();

            // Handle client identification
            String initial = reader.readLine();
            if (!handleClientIdentification(initial)) {
                return; // Identification failed, connection closed
            }

            // Main command processing loop
            processCommands();

        } catch (SocketException e) {
            logger.log(Logger.Level.INFO, "ClientHandler", "Connection closed by client: " + clientName);
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection lost with " + clientName, e);
        } finally {
            cleanup();
        }
    }

    /**
     * Creates and initializes the IO streams
     */
    private void createStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
        dataOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
    }

    /**
     * Handles the client identification process
     * @param identificationMessage The message sent by the client for identification
     * @return true if identification was successful, false otherwise
     */
    private boolean handleClientIdentification(String identificationMessage) throws IOException {
        if (identificationMessage != null && identificationMessage.startsWith(CLIENT_ID_PREFIX)) {
            String[] parts = identificationMessage.split("\\s+", 2);
            if (parts.length == 2) {
                clientName = parts[1].trim();

                // Don't broadcast notifications for utility connections (with _upload, _download, etc.)
                if (!Config.isUtilityConnection(clientName)) {
                    broadcaster.register(clientName, writer);
                }

                writer.write("You can now use: UPLOAD <filename>, DOWNLOAD <filename>, LIST, LOGS [count]\n");
                writer.flush();
                return true;
            } else {
                writer.write("Missing client name. Connection closed.\n");
                writer.flush();
                return false;
            }
        } else {
            writer.write("Missing CLIENT_ID. Connection closed.\n");
            writer.flush();
            return false;
        }
    }

    /**
     * Main loop to process client commands
     */
    private void processCommands() throws IOException {
        String line;
        while (running && (line = reader.readLine()) != null) {
            logger.log(Logger.Level.INFO, "ClientHandler", clientName + " issued command: " + line);

            StringTokenizer tokenizer = new StringTokenizer(line);
            if (!tokenizer.hasMoreTokens()) continue;

            String command = tokenizer.nextToken().toUpperCase();

            try {
                switch (command) {
                    case CMD_UPLOAD:
                        handleUpload(tokenizer);
                        break;

                    case CMD_DOWNLOAD:
                        handleDownload(tokenizer);
                        break;

                    case CMD_LIST:
                        handleList();
                        break;

                    case CMD_LOGS:
                        handleLogs(tokenizer);
                        break;

                    default:
                        writer.write(ERR_UNKNOWN_COMMAND + "\n");
                        writer.flush();
                        break;
                }
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR, "ClientHandler", "Error processing command: " + command, e);
                try {
                    writer.write(String.format(ERR_COMMAND_FAILED, e.getMessage()) + "\n");
                    writer.flush();
                } catch (IOException ioe) {
                    // Connection probably lost, breaking out of the loop
                    logger.log(Logger.Level.ERROR, "ClientHandler",
                            "Failed to send error message, connection may be lost", ioe);
                    break;
                }
            }
        }
    }

    /**
     * Handles the UPLOAD command
     */
    private void handleUpload(StringTokenizer tokenizer) throws IOException {
        if (!tokenizer.hasMoreTokens()) {
            writer.write(String.format(ERR_MISSING_FILENAME, "UPLOAD") + "\n");
            writer.flush();
            return;
        }

        String uploadFilename = tokenizer.nextToken();

        if (!uploadFilename.toLowerCase().endsWith(".txt")) {
            writer.write(ERR_ONLY_TXT + "\n");
            writer.flush();
            return;
        }

        try {
            // Increase timeout for file operations
            SocketUtils.configureFileTransferSocket(socket);

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
            writer.write(SUCCESS_UPLOAD + "\n");
            writer.flush();

            // Only broadcast notifications for regular clients, not special connections
            String baseClientName = getBaseClientName();

            // Use the improved broadcaster method for file upload notifications
            if (!Config.isUtilityConnection(clientName)) {
                broadcaster.broadcastFileUpload(baseClientName, uploadFilename);
            }

            // Reset timeout to normal
            SocketUtils.configureStandardSocket(socket);
        } catch (RuntimeException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Upload failed: " + e.getMessage(), e);
            writer.write(String.format(ERR_UPLOAD_FAILED, e.getMessage()) + "\n");
            writer.flush();

            // Reset timeout to normal
            SocketUtils.configureStandardSocket(socket);
        }
    }

    /**
     * Handles the DOWNLOAD command
     */
    private void handleDownload(StringTokenizer tokenizer) throws IOException {
        if (!tokenizer.hasMoreTokens()) {
            writer.write(String.format(ERR_MISSING_FILENAME, "DOWNLOAD") + "\n");
            writer.flush();
            return;
        }

        String downloadFilename = tokenizer.nextToken();

        try {
            // Increase timeout during file transfer
            SocketUtils.configureFileTransferSocket(socket);

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

            writer.write(SUCCESS_DOWNLOAD + "\n");
            writer.flush();

            // Only broadcast notifications for regular clients, not special connections
            String baseClientName = getBaseClientName();

            // Use the improved broadcaster method for file download notifications
            if (!Config.isUtilityConnection(clientName)) {
                broadcaster.broadcastFileDownload(baseClientName, downloadFilename);
            }

            // Reset timeout to normal
            SocketUtils.configureStandardSocket(socket);
        } catch (RuntimeException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Error sending file: " + e.getMessage(), e);
            writer.write(String.format(ERR_DOWNLOAD_FAILED, e.getMessage()) + "\n");
            writer.flush();

            // Reset timeout to normal
            SocketUtils.configureStandardSocket(socket);
        }
    }

    /**
     * Handles the LIST command
     */
    private void handleList() throws IOException {
        String fileList = fileManager.listFiles();

        // Add a clear end marker to help client know when response is complete
        if (fileList.contains("No files available")) {
            writer.write("Available files:\nNo files available on the server.\n" + RESPONSE_END_MARKER + "\n");
        } else {
            // Send the file list with a clear end marker
            writer.write(fileList.trim() + "\n" + RESPONSE_END_MARKER + "\n");
        }

        writer.flush();
    }

    /**
     * Handles the LOGS command
     */
    private void handleLogs(StringTokenizer tokenizer) throws IOException {
        // Default number of logs to show
        int logCount = 10;

        // Check if user specified a count
        if (tokenizer.hasMoreTokens()) {
            try {
                logCount = Integer.parseInt(tokenizer.nextToken());
                // Enforce reasonable limits
                if (logCount < 1) logCount = 1;
                if (logCount > 100) logCount = 100;
            } catch (NumberFormatException e) {
                // Ignore invalid numbers, use default
            }
        }

        // Get logs from database
        String logs = DBLogger.getRecentLogs(logCount);

        // Send to client with end marker
        writer.write(logs + RESPONSE_END_MARKER + "\n");
        writer.flush();
    }

    /**
     * Extracts the base client name without suffixes
     */
    private String getBaseClientName() {
        if (clientName.contains("_")) {
            return clientName.substring(0, clientName.indexOf("_"));
        }
        return clientName;
    }

    /**
     * Cleans up resources when the handler is done
     */
    private void cleanup() {
        closeResources();

        // Only unregister regular clients, not special connections
        if (clientName != null && !clientName.isEmpty() && !Config.isUtilityConnection(clientName)) {
            broadcaster.unregister(clientName);
        }
    }

    /**
     * Closes all resources safely
     */
    private void closeResources() {
        ResourceUtils.safeClose(reader, "reader", logger);
        ResourceUtils.safeClose(writer, "writer", logger);
        ResourceUtils.safeClose(dataInputStream, "data input stream", logger);
        ResourceUtils.safeClose(dataOutputStream, "data output stream", logger);

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                logger.log(Logger.Level.INFO, "ClientHandler", "Socket closed for client: " + clientName);
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "ClientHandler", "Error closing socket", e);
            }
        }

        logger.log(Logger.Level.INFO, "ClientHandler", "All resources cleaned up for client: " + clientName);
    }
}