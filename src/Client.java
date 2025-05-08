import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class Client {
    private static final Logger logger = Logger.getInstance();

    // Configuration values
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final int SOCKET_TIMEOUT = Config.getSocketTimeout();
    private static final int FILE_TRANSFER_TIMEOUT = Config.getFileTransferTimeout();

    // Command constants from Config
    private static final String CMD_UPLOAD = Config.Protocol.CMD_UPLOAD;
    private static final String CMD_DOWNLOAD = Config.Protocol.CMD_DOWNLOAD;
    private static final String CMD_LIST = Config.Protocol.CMD_LIST;
    private static final String CMD_LOGS = Config.Protocol.CMD_LOGS;
    private static final String CLIENT_ID_PREFIX = Config.Protocol.CLIENT_ID_PREFIX;
    private static final String NOTIFICATION_PREFIX = Config.Protocol.NOTIFICATION_PREFIX;
    private static final String RESPONSE_END_MARKER = Config.Protocol.RESPONSE_END_MARKER;

    // Error messages
    private static final String ERR_TIMEOUT = "Connection timed out: %s";
    private static final String ERR_CONNECTION_LOST = "Connection lost: %s";
    private static final String ERR_CONNECTION_ERROR = "Connection error: %s";
    private static final String ERR_MISSING_FILENAME = "Missing filename for %s command.";
    private static final String ERR_FILE_NOT_FOUND = "File not found: %s";
    private static final String ERR_ONLY_TXT = "Only .txt files are allowed for upload.";
    private static final String ERR_ENCODE_FILENAME = "Failed to encode filename: %s";
    private static final String ERR_UPLOAD_FAILED = "Upload failed: %s";
    private static final String ERR_DOWNLOAD_FAILED = "Download failed: %s";
    private static final String ERR_INVALID_FILESIZE = "Invalid file size reported: %s";
    private static final String ERR_INVALID_CHECKSUM = "Invalid checksum length: %s";
    private static final String ERR_EOF_DOWNLOAD = "Unexpected end of file during download";
    private static final String ERR_CORRUPTED = "Downloaded file is corrupted. Checksum verification failed.";
    private static final String ERR_SAVE_FAILED = "Failed to save downloaded file";
    private static final String ERR_SERVER_TIMEOUT = "Server response timeout. Try again or check server connection.";
    private static final String ERR_INVALID_COMMAND = "Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, LOGS [count]";

    // Info messages
    private static final String INFO_COMMAND_USAGE = "Usage: %s <filepath> or %s \"<filepath with spaces>\"";
    private static final String INFO_STARTING = "Starting client...";
    private static final String INFO_CONNECTED = "Connected to server at %s:%d";
    private static final String INFO_PROCESSING = "Processing %s command for: %s";
    private static final String INFO_PREPARING = "Preparing to upload: %s (%d bytes)";
    private static final String INFO_PROGRESS = "%s progress: %d%%";
    private static final String INFO_COMPLETED = "%s completed.";
    private static final String INFO_RECEIVING = "Receiving file: %s (%d bytes)";
    private static final String INFO_DOWNLOAD_SAVED = "Downloaded %s to folder: downloads/";
    private static final String INFO_FILE_PATH = "File saved to: %s";
    private static final String INFO_REQUEST_LIST = "Requesting file list from server...";
    private static final String INFO_REQUEST_LOGS = "Requesting %d recent logs from server...";
    private static final String SUCCESS_UPLOADED = "File '%s' was successfully uploaded to the server database.";

    private static final String PROMPT = "> ";
    private static final String CLIENT_FILES_DIR = "client_files/";
    private static final String DOWNLOADS_DIR = "downloads/";

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", INFO_STARTING);

        String serverHost = Config.getServerHost();
        int serverPort = Config.getServerPort();

        try (Socket socket = new Socket(serverHost, serverPort)) {
            // Configure socket for better stability
            SocketUtils.configureStandardSocket(socket);

            System.out.println(String.format(INFO_CONNECTED, serverHost, serverPort));
            logger.log(Logger.Level.INFO, "Client", String.format(INFO_CONNECTED, serverHost, serverPort));

            // Set up communication streams
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            // Read welcome message
            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);

            // Get client name from user
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter your client name: ");
            String clientName = scanner.nextLine().trim();
            writer.write(CLIENT_ID_PREFIX + clientName + "\n");
            writer.flush();

            // Read server response with available commands
            String response = reader.readLine();
            System.out.println(response);

            // Start notification listener thread
            Thread notificationThread = startNotificationListener(reader);

            // Main command loop
            processUserCommands(scanner, writer, reader, serverHost, serverPort, clientName, socket);

        } catch (SocketTimeoutException e) {
            System.out.println(String.format(ERR_TIMEOUT, e.getMessage()));
            logger.log(Logger.Level.ERROR, "Client", "Connection timed out", e);
        } catch (SocketException e) {
            System.out.println(String.format(ERR_CONNECTION_LOST, e.getMessage()));
            logger.log(Logger.Level.ERROR, "Client", "Connection lost", e);
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "Client", "Connection error", e);
            System.out.println(String.format(ERR_CONNECTION_ERROR, e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * Starts a thread to listen for server notifications
     */
    private static Thread startNotificationListener(BufferedReader reader) {
        Thread notificationThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // Check if there's data available to read
                    if (reader.ready()) {
                        String notification = reader.readLine();
                        if (notification != null && notification.startsWith(NOTIFICATION_PREFIX)) {
                            // Clear the current line and print the notification
                            System.out.print("\r");  // Carriage return to start of line
                            System.out.println("\n[NOTIFICATION]" +
                                    notification.substring(NOTIFICATION_PREFIX.length()).trim());
                            System.out.print(PROMPT);  // Reprint the prompt
                        }
                    }
                    // Sleep briefly to avoid consuming too much CPU
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException e) {
                // Thread is terminating, no need to log the error
            }
        });

        // Set as daemon thread so it terminates when the main thread exits
        notificationThread.setDaemon(true);
        notificationThread.start();

        return notificationThread;
    }

    /**
     * Main loop to process user commands
     */
    private static void processUserCommands(Scanner scanner, BufferedWriter writer,
                                            BufferedReader reader, String serverHost, int serverPort,
                                            String clientName, Socket socket) throws IOException {

        while (true) {
            System.out.print(PROMPT);
            String command = scanner.nextLine().trim();

            if (command.isEmpty()) {
                continue;
            }

            // Get the command without parsing
            String[] parts = command.split("\\s+", 2);
            String cmd = parts[0].toUpperCase();

            switch (cmd) {
                case CMD_UPLOAD:
                    handleUploadCommand(command, serverHost, serverPort, clientName);
                    break;

                case CMD_DOWNLOAD:
                    handleDownloadCommand(command, serverHost, serverPort, clientName);
                    break;

                case CMD_LIST:
                    handleListCommand(writer, socket, reader);
                    break;

                case CMD_LOGS:
                    handleLogsCommand(command, writer, socket, reader);
                    break;

                default:
                    System.out.println(ERR_INVALID_COMMAND);
            }
        }
    }

    /**
     * Handles the UPLOAD command
     */
    private static void handleUploadCommand(String command, String serverHost,
                                            int serverPort, String clientName) {

        // Extract the file path from the command
        String filePath = command.substring(CMD_UPLOAD.length()).trim();

        // Remove quotes if present
        if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
            filePath = filePath.substring(1, filePath.length() - 1);
        }

        // Check if the path is not empty
        if (filePath.isEmpty()) {
            System.out.println(String.format(ERR_MISSING_FILENAME, CMD_UPLOAD));
            System.out.println(String.format(INFO_COMMAND_USAGE, CMD_UPLOAD, CMD_UPLOAD));
        } else {
            handleUpload(filePath, serverHost, serverPort, clientName);
        }
    }

    /**
     * Handles the DOWNLOAD command
     */
    private static void handleDownloadCommand(String command, String serverHost,
                                              int serverPort, String clientName) {

        // Extract the filename from the command
        String filename = command.substring(CMD_DOWNLOAD.length()).trim();

        // Remove quotes if present
        if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
        }

        // Check if the path is not empty
        if (filename.isEmpty()) {
            System.out.println(String.format(ERR_MISSING_FILENAME, CMD_DOWNLOAD));
            System.out.println(String.format(INFO_COMMAND_USAGE, CMD_DOWNLOAD, CMD_DOWNLOAD));
        } else {
            handleDownload(filename, serverHost, serverPort, clientName);
        }
    }

    /**
     * Handles the LIST command
     */
    private static void handleListCommand(BufferedWriter writer, Socket socket,
                                          BufferedReader reader) throws IOException {

        writer.write(CMD_LIST + "\n");
        writer.flush();
        System.out.println(INFO_REQUEST_LIST);

        // Read LIST response with improved handling
        readServerResponse(socket, reader);
    }

    /**
     * Handles the LOGS command
     */
    private static void handleLogsCommand(String command, BufferedWriter writer,
                                          Socket socket, BufferedReader reader) throws IOException {

        // Get log count if specified
        int logCount = 10; // Default
        String[] parts = command.split("\\s+", 2);
        if (parts.length > 1) {
            try {
                logCount = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                // Ignore invalid numbers
            }
        }

        writer.write(CMD_LOGS + " " + logCount + "\n");
        writer.flush();
        System.out.println(String.format(INFO_REQUEST_LOGS, logCount));

        // Read LOGS response
        readServerResponse(socket, reader);
    }

    /**
     * Handles file upload with improved performance
     */
    private static void handleUpload(String filePath, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(INFO_PROCESSING, "UPLOAD", filePath));

        // Handle the file path - now supports full paths
        File file = new File(filePath);

        if (!file.exists()) {
            // If the file doesn't exist with the full path, try looking in client_files
            file = new File(CLIENT_FILES_DIR + filePath);

            if (!file.exists()) {
                System.out.println(String.format(ERR_FILE_NOT_FOUND, filePath));
                System.out.println("Please provide a valid file path or place the file in the " +
                        CLIENT_FILES_DIR + " directory.");
                return;
            }
        }

        // Get just the filename for the server (without path)
        String filename = file.getName();

        // Check file extension
        if (!filename.toLowerCase().endsWith(".txt")) {
            System.out.println(ERR_ONLY_TXT);
            return;
        }

        // URL encode the filename to safely transmit it
        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        long fileSize = file.length();
        System.out.println(String.format(INFO_PREPARING, filename, fileSize));

        // Create a new socket for the file transfer
        try (Socket uploadSocket = new Socket(serverHost, serverPort)) {
            // Configure upload socket with longer timeout for larger files
            SocketUtils.configureFileTransferSocket(uploadSocket);

            // Create streams for the upload socket
            BufferedReader uploadReader = new BufferedReader(
                    new InputStreamReader(uploadSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter uploadWriter = new BufferedWriter(
                    new OutputStreamWriter(uploadSocket.getOutputStream(), StandardCharsets.UTF_8));
            DataOutputStream uploadDataOut = new DataOutputStream(
                    new BufferedOutputStream(uploadSocket.getOutputStream(), BUFFER_SIZE));

            // Skip the welcome message
            uploadReader.readLine();

            // Identify to the server
            uploadWriter.write(CLIENT_ID_PREFIX + clientName + "_upload\n");
            uploadWriter.flush();

            // Skip the available commands message
            uploadReader.readLine();

            // Send the UPLOAD command
            uploadWriter.write(CMD_UPLOAD + " " + encodedFilename + "\n");
            uploadWriter.flush();

            // Ensure protocol synchronization with small delay
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Send file size
            uploadDataOut.writeLong(fileSize);

            // Calculate and send checksum
            byte[] checksum = calculateChecksum(file);
            uploadDataOut.writeInt(checksum.length);
            uploadDataOut.write(checksum);
            uploadDataOut.flush();

            // Upload with progress reporting using larger buffer
            try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                long total = 0;
                int lastPercentageReported = 0;

                while ((count = fileIn.read(buffer)) > 0) {
                    uploadDataOut.write(buffer, 0, count);
                    total += count;
                    int currentPercentage = (int)(100 * total / (double)fileSize);

                    // Report progress in 10% increments
                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println(String.format(INFO_PROGRESS, "Upload", currentPercentage));
                    }
                }
                uploadDataOut.flush();
                System.out.println(String.format(INFO_COMPLETED, "File upload"));
            }

            // Wait for server response
            try {
                uploadSocket.setSoTimeout(10000); // 10 second timeout for response
                String uploadResponse = uploadReader.readLine();
                if (uploadResponse != null) {
                    System.out.println(uploadResponse);
                }
                System.out.println(String.format(SUCCESS_UPLOADED, filename));
            } catch (SocketTimeoutException e) {
                // Assume success if no error occurred during transfer
                System.out.println(String.format(SUCCESS_UPLOADED, filename));
            }

            System.out.println(String.format(INFO_COMPLETED, "Upload"));
        } catch (Exception e) {
            System.out.println(String.format(ERR_UPLOAD_FAILED, e.getMessage()));
        }
    }

    /**
     * Handles file download with improved performance
     */
    private static void handleDownload(String filename, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(INFO_PROCESSING, "DOWNLOAD", filename));

        // URL encode the filename for safe transmission
        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        // Create a dedicated connection for downloading
        try (Socket downloadSocket = new Socket(serverHost, serverPort)) {
            // Configure download socket
            SocketUtils.configureFileTransferSocket(downloadSocket);

            // Create streams with larger buffers
            BufferedReader downloadReader = new BufferedReader(
                    new InputStreamReader(downloadSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter downloadWriter = new BufferedWriter(
                    new OutputStreamWriter(downloadSocket.getOutputStream(), StandardCharsets.UTF_8));
            DataInputStream downloadDataIn = new DataInputStream(
                    new BufferedInputStream(downloadSocket.getInputStream(), BUFFER_SIZE));

            // Skip the welcome message
            downloadReader.readLine();

            // Identify to the server
            downloadWriter.write(CLIENT_ID_PREFIX + clientName + "_download\n");
            downloadWriter.flush();

            // Skip the available commands message
            downloadReader.readLine();

            System.out.println(String.format(INFO_PROCESSING, "download", filename));

            // Send download command
            downloadWriter.write(CMD_DOWNLOAD + " " + encodedFilename + "\n");
            downloadWriter.flush();

            // Ensure protocol synchronization with small delay
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Read file size
            long fileSize = downloadDataIn.readLong();

            if (fileSize < 0) {
                System.out.println(String.format(ERR_FILE_NOT_FOUND, filename));
                return;
            }

            // Validate file size
            if (fileSize > 10_000_000_000L) { // 10GB max as sanity check
                System.out.println(String.format(ERR_INVALID_FILESIZE, fileSize));
                return;
            }

            System.out.println(String.format(INFO_RECEIVING, filename, fileSize));

            // Receive checksum
            int checksumLength = downloadDataIn.readInt();

            if (checksumLength <= 0 || checksumLength > 64) {
                System.out.println(String.format(ERR_INVALID_CHECKSUM, checksumLength));
                return;
            }

            byte[] expectedChecksum = new byte[checksumLength];
            downloadDataIn.readFully(expectedChecksum);

            // Create downloads directory if it doesn't exist
            File dir = new File(DOWNLOADS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Use a temporary file for downloading
            String tempFileName = "temp_" + System.currentTimeMillis() + "_" + filename;
            File tempFile = new File(dir, tempFileName);
            File outFile = new File(dir, filename);

            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE]; // Increased buffer size
                long remaining = fileSize;
                int count;
                int lastPercentageReported = 0;

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    count = downloadDataIn.read(buffer, 0, toRead);

                    if (count < 0) {
                        throw new IOException(ERR_EOF_DOWNLOAD);
                    }

                    fileOut.write(buffer, 0, count);
                    remaining -= count;

                    // Report progress in 10% increments
                    int currentPercentage = (int)(100 * (fileSize - remaining) / (double)fileSize);
                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println(String.format(INFO_PROGRESS, "Download", currentPercentage));
                    }
                }
                fileOut.flush();
            }

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(tempFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                System.out.println(ERR_CORRUPTED);
                tempFile.delete(); // Delete corrupted file
            } else {
                // If checksum matches, rename temp file to final name
                if (outFile.exists()) {
                    outFile.delete(); // Delete any existing file with same name
                }

                boolean renamed = tempFile.renameTo(outFile);

                if (renamed) {
                    System.out.println(String.format(INFO_DOWNLOAD_SAVED, filename));
                    System.out.println(String.format(INFO_FILE_PATH, outFile.getAbsolutePath()));
                } else {
                    System.out.println(ERR_SAVE_FAILED);
                    tempFile.delete();
                }
            }

            // Check for server confirmation
            try {
                downloadSocket.setSoTimeout(5000); // 5 second timeout for response
                String downloadResponse = downloadReader.readLine();
                if (downloadResponse != null) {
                    System.out.println(downloadResponse);
                }
            } catch (SocketTimeoutException e) {
                // It's okay if we don't get a response after download
            }

            System.out.println(String.format(INFO_COMPLETED, "Download"));
        } catch (Exception e) {
            System.out.println(String.format(ERR_DOWNLOAD_FAILED, e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * Improved method to read server responses with better timeout handling
     */
    private static void readServerResponse(Socket socket, BufferedReader reader) throws IOException {
        // Set a longer timeout for command responses
        int originalTimeout = socket.getSoTimeout();
        socket.setSoTimeout(30000); // 30 second timeout (increased from 15)

        try {
            String line;
            int lineCount = 0;
            boolean endMarkerFound = false;

            while (!endMarkerFound && lineCount < 100) {
                // Check if there's data available or wait for timeout
                line = reader.readLine();

                if (line == null) {
                    // End of stream
                    break;
                }

                // Check for end marker
                if (line.equals(RESPONSE_END_MARKER)) {
                    endMarkerFound = true;
                    continue;
                }

                // Print the line directly (unless it's an empty line after some content)
                if (!(line.isEmpty() && lineCount > 0)) {
                    System.out.println(line);
                }

                lineCount++;
            }

            if (lineCount == 0) {
                System.out.println("No response received from server.");
            }
        } catch (SocketTimeoutException e) {
            System.out.println(ERR_SERVER_TIMEOUT);
        } finally {
            // Reset to original timeout
            socket.setSoTimeout(originalTimeout);
        }
    }

    /**
     * Calculates SHA-256 checksum of a file
     */
    private static byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = fis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }
}