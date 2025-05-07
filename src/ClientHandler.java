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

            // Set socket timeout to detect disconnections faster
            socket.setSoTimeout(30000); // 30 seconds timeout
            logger.log(Logger.Level.INFO, "ClientHandler", "Set socket timeout to 30 seconds");

            // Create streams
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataInputStream = new DataInputStream(socket.getInputStream());
            dataOutputStream = new DataOutputStream(socket.getOutputStream());
            logger.log(Logger.Level.INFO, "ClientHandler", "All streams initialized");

            writer.write("Welcome to the File Server. Please identify using CLIENT_ID <name>\n");
            writer.flush();
            logger.log(Logger.Level.INFO, "ClientHandler", "Sent welcome message");

            String initial = reader.readLine();
            logger.log(Logger.Level.INFO, "ClientHandler", "Received initial message: " + initial);

            if (initial != null && initial.startsWith("CLIENT_ID")) {
                String[] parts = initial.split("\\s+", 2);
                if (parts.length == 2) {
                    clientName = parts[1].trim();
                    broadcaster.register(clientName, writer);
                    logger.log(Logger.Level.INFO, "ClientHandler", "Client registered with name: " + clientName);
                } else {
                    writer.write("Missing client name. Connection closed.\n");
                    writer.flush();
                    logger.log(Logger.Level.WARNING, "ClientHandler", "Missing client name in CLIENT_ID command");
                    return;
                }
                logger.log(Logger.Level.INFO, "ClientHandler", "Client identified as: " + clientName);
            } else {
                writer.write("Missing CLIENT_ID. Connection closed.\n");
                writer.flush();
                logger.log(Logger.Level.WARNING, "ClientHandler", "Missing CLIENT_ID command");
                return;
            }

            writer.write("You can now use: UPLOAD <filename>, DOWNLOAD <filename>, LIST\n");
            writer.flush();
            logger.log(Logger.Level.INFO, "ClientHandler", "Sent available commands");

            String line;
            while (running && (line = reader.readLine()) != null) {
                logger.log(Logger.Level.INFO, "ClientHandler", clientName + " issued command: " + line);
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) continue;

                String command = tokenizer.nextToken().toUpperCase();
                logger.log(Logger.Level.INFO, "ClientHandler", "Processing command: " + command);

                try {
                    switch (command) {
                        case "UPLOAD":
                            if (!tokenizer.hasMoreTokens()) {
                                writer.write("Missing filename for UPLOAD.\n");
                                writer.flush();
                                logger.log(Logger.Level.WARNING, "ClientHandler", "Missing filename for UPLOAD command");
                                break;
                            }

                            String uploadFilename = tokenizer.nextToken();
                            logger.log(Logger.Level.INFO, "ClientHandler", "Upload requested for file: " + uploadFilename);

                            if (!uploadFilename.toLowerCase().endsWith(".txt")) {
                                writer.write("ERROR: Only .txt files are allowed.\n");
                                writer.flush();
                                logger.log(Logger.Level.WARNING, "ClientHandler", "Rejected non-txt file: " + uploadFilename);
                                break;
                            }

                            try {
                                logger.log(Logger.Level.INFO, "ClientHandler", "Beginning upload process for: " + uploadFilename);

                                // Use dataInputStream directly instead of socket.getInputStream()
                                logger.log(Logger.Level.INFO, "ClientHandler", "Calling fileManager.receiveFile()");
                                fileManager.receiveFile(uploadFilename, dataInputStream);
                                logger.log(Logger.Level.INFO, "ClientHandler", "File received successfully: " + uploadFilename);

                                // Log the successful upload
                                DBLogger.log(clientName, "UPLOAD", uploadFilename);
                                logger.log(Logger.Level.INFO, "ClientHandler", "Added to database logs");

                                // IMPORTANT: Make sure any data in the binary stream is flushed before sending text response
                                dataOutputStream.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Flushed data output stream");

                                // Add a short delay to ensure streams are synchronized
                                try {
                                    Thread.sleep(200);
                                    logger.log(Logger.Level.INFO, "ClientHandler", "Waited 200ms after receiving file");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    logger.log(Logger.Level.WARNING, "ClientHandler", "Sleep interrupted: " + e.getMessage());
                                }

                                // Make sure output buffer is clear
                                try {
                                    // Just flush without checking available() which doesn't exist on OutputStream
                                    socket.getOutputStream().flush();
                                    logger.log(Logger.Level.INFO, "ClientHandler", "Flushed output stream");
                                } catch (IOException e) {
                                    logger.log(Logger.Level.WARNING, "ClientHandler", "Error flushing output: " + e.getMessage());
                                }

                                // Send response to client - make sure this reaches the client
                                String successMessage = "Upload successful to server_files/.\n";
                                writer.write(successMessage);
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent success response: " + successMessage.trim());

                                logger.log(Logger.Level.INFO, "ClientHandler",
                                        "Upload successful for " + clientName + ": " + uploadFilename);

                                // Notify all other clients
                                broadcaster.broadcast("[UPLOAD] " + clientName + " uploaded: " + uploadFilename);
                                logger.log(Logger.Level.INFO, "ClientHandler", "Broadcast upload notification to other clients");
                            } catch (RuntimeException e) {
                                logger.log(Logger.Level.ERROR, "ClientHandler", "Upload failed: " + uploadFilename + ", Error: " + e.getMessage(), e);

                                try {
                                    // Ensure we can send a response by short delay
                                    Thread.sleep(200);
                                    logger.log(Logger.Level.INFO, "ClientHandler", "Waited 200ms after error");
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }

                                writer.write("ERROR: Upload failed: " + e.getMessage() + "\n");
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent error response to client");
                            }
                            break;

                        case "DOWNLOAD":
                            if (!tokenizer.hasMoreTokens()) {
                                writer.write("ERROR: Please enter a filename to download\n");
                                writer.flush();
                                logger.log(Logger.Level.WARNING, "ClientHandler", "Missing filename for DOWNLOAD command");
                                break;
                            }
                            String downloadFilename = tokenizer.nextToken();
                            logger.log(Logger.Level.INFO, "ClientHandler", "Download requested for file: " + downloadFilename);

                            File requestedFile = new File(FileManager.SHARED_DIR + downloadFilename);
                            logger.log(Logger.Level.INFO, "ClientHandler", "Looking for file at: " + requestedFile.getAbsolutePath());

                            if (!requestedFile.exists() || !requestedFile.isFile()) {
                                writer.write("ERROR: File '" + downloadFilename + "' not found on server.\n");
                                writer.flush();
                                logger.log(Logger.Level.WARNING, "ClientHandler",
                                        "Download failed - file not found: " + downloadFilename +
                                                " (exists: " + requestedFile.exists() + ", isFile: " +
                                                (requestedFile.exists() ? requestedFile.isFile() : "N/A") + ")");

                                // Send -1 as file size to indicate file not found
                                dataOutputStream.writeLong(-1);
                                dataOutputStream.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent file not found signal (-1)");
                                break;
                            }

                            try {
                                // Increase timeout during file transfer
                                socket.setSoTimeout(120000); // 2 minutes timeout for large files
                                logger.log(Logger.Level.INFO, "ClientHandler", "Increased socket timeout to 120 seconds for file transfer");

                                // Use the fileManager to send the file
                                // This handles all the protocol details
                                logger.log(Logger.Level.INFO, "ClientHandler", "Calling fileManager.sendFile()");
                                fileManager.sendFile(downloadFilename, socket.getOutputStream());
                                logger.log(Logger.Level.INFO, "ClientHandler", "File sent successfully: " + downloadFilename);

                                // Reset timeout to normal
                                socket.setSoTimeout(30000);
                                logger.log(Logger.Level.INFO, "ClientHandler", "Reset socket timeout to 30 seconds");

                                // Log the download
                                DBLogger.log(clientName, "DOWNLOAD", downloadFilename);
                                logger.log(Logger.Level.INFO, "ClientHandler", "Added download to database logs");

                                logger.log(Logger.Level.INFO, "ClientHandler",
                                        "Successfully sent file: " + downloadFilename);

                                // Add a short delay before sending text response
                                try {
                                    Thread.sleep(200);
                                    logger.log(Logger.Level.INFO, "ClientHandler", "Waited 200ms after sending file");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }

                                writer.write("Download completed successfully\n");
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent download success message");

                            } catch (RuntimeException e) {
                                logger.log(Logger.Level.ERROR, "ClientHandler",
                                        "Error sending file: " + downloadFilename, e);
                                writer.write("ERROR: Failed to send file: " + e.getMessage() + "\n");
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent error message to client");
                            }
                            break;

                        case "LIST":
                            logger.log(Logger.Level.INFO, "ClientHandler", "Processing LIST command");
                            String fileList = fileManager.listFiles();
                            logger.log(Logger.Level.INFO, "ClientHandler", "Retrieved file list with " +
                                    (fileList.split("\n").length - 1) + " files");

                            // Send the file list
                            writer.write(fileList);
                            writer.write("\n");
                            writer.flush();
                            logger.log(Logger.Level.INFO, "ClientHandler", "Sent file list to client");

                            logger.log(Logger.Level.INFO, "ClientHandler", clientName + " requested file list");
                            break;

                        case "VIEWLOGS":
                            logger.log(Logger.Level.INFO, "ClientHandler", "Processing VIEWLOGS command");
                            try {
                                String logs = getRecentLogs(50);
                                logger.log(Logger.Level.INFO, "ClientHandler", "Retrieved logs with " +
                                        (logs.split("\n").length - 1) + " entries");

                                writer.write(logs);
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent logs to client");

                                logger.log(Logger.Level.INFO, "ClientHandler",
                                        clientName + " requested log history");
                            } catch (Exception e) {
                                logger.log(Logger.Level.ERROR, "ClientHandler", "Error retrieving logs: " + e.getMessage(), e);
                                writer.write("ERROR: Failed to retrieve logs: " + e.getMessage() + "\n");
                                writer.flush();
                                logger.log(Logger.Level.INFO, "ClientHandler", "Sent error message to client");
                            }
                            break;

                        case "EXIT":
                            logger.log(Logger.Level.INFO, "ClientHandler", "Client requested exit: " + clientName);
                            running = false;
                            writer.write("Goodbye!\n");
                            writer.flush();
                            break;

                        default:
                            logger.log(Logger.Level.WARNING, "ClientHandler", "Unknown command: " + command);
                            writer.write("Unknown command. Available commands: UPLOAD, DOWNLOAD, LIST, VIEWLOGS, EXIT\n");
                            writer.flush();
                            logger.log(Logger.Level.INFO, "ClientHandler", "Sent unknown command message");
                            break;
                    }
                } catch (Exception e) {
                    logger.log(Logger.Level.ERROR, "ClientHandler", "Error processing command: " + command, e);
                    try {
                        writer.write("ERROR: Command execution failed: " + e.getMessage() + "\n");
                        writer.flush();
                        logger.log(Logger.Level.INFO, "ClientHandler", "Sent error message to client");
                    } catch (IOException ioe) {
                        // Connection probably lost, breaking out of the loop
                        logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to send error message, connection may be lost", ioe);
                        break;
                    }
                }
            }

        } catch (SocketException e) {
            logger.log(Logger.Level.INFO, "ClientHandler", "Connection closed by client: " + clientName + ", Error: " + e.getMessage());
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection lost with " + clientName + ", Error: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            logger.log(Logger.Level.INFO, "ClientHandler", "Cleaning up resources for client: " + clientName);
            try {
                if (reader != null) {
                    reader.close();
                    logger.log(Logger.Level.INFO, "ClientHandler", "Closed reader");
                }
                if (writer != null) {
                    writer.close();
                    logger.log(Logger.Level.INFO, "ClientHandler", "Closed writer");
                }
                if (dataInputStream != null) {
                    dataInputStream.close();
                    logger.log(Logger.Level.INFO, "ClientHandler", "Closed data input stream");
                }
                if (dataOutputStream != null) {
                    dataOutputStream.close();
                    logger.log(Logger.Level.INFO, "ClientHandler", "Closed data output stream");
                }
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    logger.log(Logger.Level.INFO, "ClientHandler", "Closed socket");
                }
                logger.log(Logger.Level.INFO, "ClientHandler", "All resources cleaned up for client: " + clientName);
            } catch (IOException e) {
                logger.log(Logger.Level.ERROR, "ClientHandler", "Error closing resources for client: " + clientName, e);
            }
        }
    }

    private String getRecentLogs(int limit) {
        logger.log(Logger.Level.INFO, "ClientHandler", "Getting recent logs, limit: " + limit);
        try {
            // Create a direct connection to the database
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(Config.getDbUrl());
                 java.sql.Statement stmt = conn.createStatement()) {

                logger.log(Logger.Level.INFO, "ClientHandler", "Connected to database: " + Config.getDbUrl());

                // Check if logs table exists and has correct schema
                boolean tableExists = false;
                try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "logs", null)) {
                    tableExists = rs.next();
                }
                logger.log(Logger.Level.INFO, "ClientHandler", "Logs table exists: " + tableExists);

                if (!tableExists) {
                    logger.log(Logger.Level.INFO, "ClientHandler", "Creating logs table");
                    stmt.executeUpdate("""
                        CREATE TABLE logs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            client TEXT NOT NULL,
                            action TEXT NOT NULL,
                            filename TEXT NOT NULL,
                            timestamp TEXT NOT NULL
                        )
                    """);
                    return "=== File Logs (Last 50 Actions) ===\nNo logs found.\n";
                }

                // Check if client column exists
                boolean hasClientColumn = false;
                try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(logs)")) {
                    while (rs.next()) {
                        if ("client".equalsIgnoreCase(rs.getString("name"))) {
                            hasClientColumn = true;
                            break;
                        }
                    }
                }
                logger.log(Logger.Level.INFO, "ClientHandler", "Logs table has client column: " + hasClientColumn);

                // Rebuild table if client column is missing
                if (!hasClientColumn) {
                    logger.log(Logger.Level.INFO, "ClientHandler", "Rebuilding logs table to add missing client column");
                    // Create backup of existing table
                    stmt.executeUpdate("CREATE TABLE logs_backup AS SELECT * FROM logs");
                    stmt.executeUpdate("DROP TABLE logs");
                    stmt.executeUpdate("""
                        CREATE TABLE logs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            client TEXT NOT NULL,
                            action TEXT NOT NULL,
                            filename TEXT NOT NULL,
                            timestamp TEXT NOT NULL
                        )
                    """);

                    // Try to migrate data
                    try {
                        stmt.executeUpdate("""
                            INSERT INTO logs (client, action, filename, timestamp)
                            SELECT 'Unknown', action, filename, timestamp FROM logs_backup
                        """);
                        logger.log(Logger.Level.INFO, "ClientHandler", "Migrated data from backup table");
                    } catch (Exception e) {
                        // Migration failed, but we can continue with empty table
                        logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to migrate logs data: " + e.getMessage(), e);
                    }
                }

                // Get logs from the table
                StringBuilder builder = new StringBuilder();
                builder.append("=== File Logs (Last 50 Actions) ===\n");

                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SELECT * FROM logs ORDER BY timestamp DESC LIMIT " + limit)) {
                    boolean hasLogs = false;
                    int logCount = 0;

                    while (rs.next()) {
                        hasLogs = true;
                        logCount++;
                        builder.append("[")
                                .append(rs.getString("timestamp"))
                                .append("] ")
                                .append(rs.getString("client"))
                                .append(" ")
                                .append(rs.getString("action"))
                                .append(": ")
                                .append(rs.getString("filename"))
                                .append("\n");
                    }

                    logger.log(Logger.Level.INFO, "ClientHandler", "Retrieved " + logCount + " log entries");

                    if (!hasLogs) {
                        builder.append("No logs found.\n");
                        logger.log(Logger.Level.INFO, "ClientHandler", "No logs found in database");
                    }
                }

                return builder.toString();
            }
        } catch (Exception e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to retrieve logs: " + e.getMessage(), e);
            return "=== File Logs (Last 50 Actions) ===\nError retrieving logs: " + e.getMessage() + "\n";
        }
    }

    private byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        logger.log(Logger.Level.INFO, "ClientHandler", "Calculating checksum for: " + file.getName());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            long totalBytes = 0;
            while ((count = fis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
                totalBytes += count;
            }
            logger.log(Logger.Level.INFO, "ClientHandler", "Checksum calculated for " + totalBytes + " bytes");
        }
        return digest.digest();
    }
}