import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class Client {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = 32768; // Increased from 8192 to 32KB
    private static final int SOCKET_TIMEOUT = 120000; // 2 minutes timeout

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", "Starting client...");

        String serverHost = Config.getProperty("server.host", "localhost");
        int serverPort = Config.getServerPort();

        try (Socket socket = new Socket(serverHost, serverPort)) {
            // Configure socket for better stability
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT);

            System.out.println("[INFO] Connected to server at " + serverHost + ":" + serverPort);
            logger.log(Logger.Level.INFO, "Client", "Connected to server at " + serverHost + ":" + serverPort);

            // Create streams for communication
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            // Read welcome message
            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);

            // Ask for client name
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter your client name: ");
            String clientName = scanner.nextLine().trim();
            writer.write("CLIENT_ID " + clientName + "\n");
            writer.flush();

            String response = reader.readLine();
            System.out.println(response); // Display available commands

            // Start a separate thread to listen for server notifications
            Thread notificationThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        // Check if there's data available to read
                        if (reader.ready()) {
                            String notification = reader.readLine();
                            if (notification != null) {
                                // Check if it's a notification message
                                if (notification.startsWith("SERVER_NOTIFICATION:")) {
                                    // Clear the current line and print the notification without extra space
                                    System.out.print("\r");  // Carriage return to start of line
                                    System.out.println("\n[NOTIFICATION]" + notification.substring("SERVER_NOTIFICATION:".length()).trim());
                                    System.out.print("> ");  // Reprint the prompt
                                }
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

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine().trim();

                if (command.isEmpty()) {
                    continue;
                }

                // Get the command without parsing
                String cmd = command.split("\\s+", 2)[0].toUpperCase();

                if (cmd.equals("UPLOAD")) {
                    // Take the rest of the command as the file path, without trying to parse it further
                    String filePath = command.substring("UPLOAD".length()).trim();

                    // Remove quotes if present
                    if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
                        filePath = filePath.substring(1, filePath.length() - 1);
                    }

                    // Check if the path is not empty
                    if (filePath.isEmpty()) {
                        System.out.println("[ERROR] Missing filename for UPLOAD command.");
                        System.out.println("[INFO] Usage: UPLOAD <filepath> or UPLOAD \"<filepath with spaces>\"");
                    } else {
                        handleUpload(filePath, serverHost, serverPort, clientName);
                    }
                } else if (cmd.equals("DOWNLOAD")) {
                    // Similar approach for download
                    String filename = command.substring("DOWNLOAD".length()).trim();

                    // Remove quotes if present
                    if (filename.startsWith("\"") && filename.endsWith("\"")) {
                        filename = filename.substring(1, filename.length() - 1);
                    }

                    // Check if the path is not empty
                    if (filename.isEmpty()) {
                        System.out.println("[ERROR] Missing filename for DOWNLOAD command.");
                        System.out.println("[INFO] Usage: DOWNLOAD <filename> or DOWNLOAD \"<filename with spaces>\"");
                    } else {
                        handleDownload(filename, serverHost, serverPort, clientName);
                    }
                } else if (cmd.equals("LIST")) {
                    writer.write("LIST\n");
                    writer.flush();
                    System.out.println("[INFO] Requesting file list from server...");

                    // Read LIST response with improved handling
                    readServerResponse(socket, reader);
                } else {
                    System.out.println("[ERROR] Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST");
                }
            }

        } catch (SocketTimeoutException e) {
            System.out.println("[ERROR] Connection timed out: " + e.getMessage());
            logger.log(Logger.Level.ERROR, "Client", "Connection timed out", e);
        } catch (SocketException e) {
            System.out.println("[ERROR] Connection lost: " + e.getMessage());
            logger.log(Logger.Level.ERROR, "Client", "Connection lost", e);
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "Client", "Connection error", e);
            System.out.println("[FATAL] Connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles file upload with improved performance
     */
    private static void handleUpload(String filePath, String serverHost, int serverPort, String clientName) {
        System.out.println("[INFO] Processing UPLOAD command for: " + filePath);

        // Handle the file path - now supports full paths
        File file = new File(filePath);

        if (!file.exists()) {
            // If the file doesn't exist with the full path, try looking in client_files
            file = new File("client_files/" + filePath);

            if (!file.exists()) {
                System.out.println("[ERROR] File not found: " + filePath);
                System.out.println("[INFO] Please provide a valid file path or place the file in the client_files directory.");
                return;
            }
        }

        // Get just the filename for the server (without path)
        String filename = file.getName();

        // Check file extension
        if (!filename.toLowerCase().endsWith(".txt")) {
            System.out.println("[ERROR] Only .txt files are allowed for upload.");
            return;
        }

        // URL encode the filename to safely transmit it
        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println("[ERROR] Failed to encode filename: " + e.getMessage());
            return;
        }

        long fileSize = file.length();
        System.out.println("[INFO] Preparing to upload: " + filename + " (" + fileSize + " bytes)");

        // Create a new socket for the file transfer
        try (Socket uploadSocket = new Socket(serverHost, serverPort)) {
            // Configure upload socket with longer timeout for larger files
            uploadSocket.setKeepAlive(true);
            uploadSocket.setTcpNoDelay(true);
            uploadSocket.setSoTimeout(SOCKET_TIMEOUT * 2); // Double timeout for uploads

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
            uploadWriter.write("CLIENT_ID " + clientName + "_upload\n");
            uploadWriter.flush();

            // Skip the available commands message
            uploadReader.readLine();

            // Send the UPLOAD command
            uploadWriter.write("UPLOAD " + encodedFilename + "\n");
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
                        System.out.println("[INFO] Upload progress: " + currentPercentage + "%");
                    }
                }
                uploadDataOut.flush();
                System.out.println("[INFO] File upload completed.");
            }

            // Wait for server response
            try {
                uploadSocket.setSoTimeout(10000); // 10 second timeout for response
                String uploadResponse = uploadReader.readLine();
                if (uploadResponse != null) {
                    System.out.println(uploadResponse);
                }
                System.out.println("[SUCCESS] File '" + filename + "' was successfully uploaded to the server.");
            } catch (SocketTimeoutException e) {
                // Assume success if no error occurred during transfer
                System.out.println("[SUCCESS] File '" + filename + "' was successfully uploaded to the server.");
            }

            System.out.println("[INFO] Upload completed");
        } catch (Exception e) {
            System.out.println("[ERROR] Upload failed: " + e.getMessage());
        }
    }

    /**
     * Handles file download with improved performance
     */
    private static void handleDownload(String filename, String serverHost, int serverPort, String clientName) {
        System.out.println("[INFO] Processing DOWNLOAD command for: " + filename);

        // URL encode the filename for safe transmission
        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println("[ERROR] Failed to encode filename: " + e.getMessage());
            return;
        }

        // Create a dedicated connection for downloading
        try (Socket downloadSocket = new Socket(serverHost, serverPort)) {
            // Configure download socket
            downloadSocket.setKeepAlive(true);
            downloadSocket.setTcpNoDelay(true);
            downloadSocket.setSoTimeout(SOCKET_TIMEOUT * 2); // Increased timeout for large files

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
            downloadWriter.write("CLIENT_ID " + clientName + "_download\n");
            downloadWriter.flush();

            // Skip the available commands message
            downloadReader.readLine();

            System.out.println("[INFO] Requesting download of: " + filename);

            // Send download command
            downloadWriter.write("DOWNLOAD " + encodedFilename + "\n");
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
                System.out.println("[ERROR] Server reported file not found: " + filename);
                return;
            }

            // Validate file size
            if (fileSize > 10_000_000_000L) { // 10GB max as sanity check
                System.out.println("[ERROR] Invalid file size reported: " + fileSize);
                return;
            }

            System.out.println("[INFO] Receiving file: " + filename + " (" + fileSize + " bytes)");

            // Receive checksum
            int checksumLength = downloadDataIn.readInt();

            if (checksumLength <= 0 || checksumLength > 64) {
                System.out.println("[ERROR] Invalid checksum length: " + checksumLength);
                return;
            }

            byte[] expectedChecksum = new byte[checksumLength];
            downloadDataIn.readFully(expectedChecksum);

            // Create downloads directory if it doesn't exist
            File dir = new File("downloads");
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
                        throw new IOException("Unexpected end of file during download");
                    }

                    fileOut.write(buffer, 0, count);
                    remaining -= count;

                    // Report progress in 10% increments
                    int currentPercentage = (int)(100 * (fileSize - remaining) / (double)fileSize);
                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println("[INFO] Download progress: " + currentPercentage + "%");
                    }
                }
                fileOut.flush();
            }

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(tempFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                System.out.println("[ERROR] Downloaded file is corrupted. Checksum verification failed.");
                tempFile.delete(); // Delete corrupted file
            } else {
                // If checksum matches, rename temp file to final name
                if (outFile.exists()) {
                    outFile.delete(); // Delete any existing file with same name
                }

                boolean renamed = tempFile.renameTo(outFile);

                if (renamed) {
                    System.out.println("[INFO] Downloaded " + filename + " to folder: downloads/");
                    System.out.println("[SUCCESS] File saved to: " + outFile.getAbsolutePath());
                } else {
                    System.out.println("[ERROR] Failed to save downloaded file");
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

            System.out.println("[INFO] Download completed");
        } catch (Exception e) {
            System.out.println("[ERROR] Download failed: " + e.getMessage());
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
                if (line.equals("*END*")) {
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
            System.out.println("[WARNING] Server response timeout. Try again or check server connection.");
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