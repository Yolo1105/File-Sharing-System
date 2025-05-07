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

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", "Starting client...");

        String serverHost = Config.getProperty("server.host", "localhost");
        int serverPort = Config.getServerPort();

        try (Socket socket = new Socket(serverHost, serverPort)) {
            // Configure socket for better stability
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60000); // 60-second timeout for socket operations

            System.out.println("[DEBUG] Socket connected with timeout set to 60 seconds");
            logger.log(Logger.Level.INFO, "Client", "Connected to server at " + serverHost + ":" + serverPort);

            // Create streams for communication
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            System.out.println("[DEBUG] Data streams initialized");

            // Read welcome message
            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);  // Display welcome message

            // Ask for client name
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter your client name: ");
            String clientName = scanner.nextLine().trim();
            writer.write("CLIENT_ID " + clientName + "\n");
            writer.flush();
            System.out.println("[DEBUG] Sent CLIENT_ID command");

            String response = reader.readLine();
            System.out.println(response); // Display available commands
            System.out.println("[DEBUG] Received server response to CLIENT_ID");

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine().trim();

                // Parse the command and handle quoted arguments
                String[] parts = parseCommand(command);

                if (parts.length == 0 || parts[0].isEmpty()) {
                    continue;
                }

                if (parts[0].equalsIgnoreCase("UPLOAD") && parts.length == 2) {
                    String filePath = parts[1];
                    System.out.println("[DEBUG] Processing UPLOAD command for: " + filePath);

                    // Handle the file path - now supports full paths
                    File file = new File(filePath);

                    if (!file.exists()) {
                        // If the file doesn't exist with the full path, try looking in client_files
                        System.out.println("[DEBUG] File not found at: " + file.getAbsolutePath());
                        file = new File("client_files/" + filePath);
                        System.out.println("[DEBUG] Trying alternative path: " + file.getAbsolutePath());

                        if (!file.exists()) {
                            System.out.println("[ERROR] File not found: " + filePath);
                            System.out.println("[DEBUG] File does not exist at either location");
                            System.out.println("[INFO] Please provide a valid file path or place the file in the client_files directory.");
                            continue;
                        }
                    }

                    // Get just the filename for the server (without path)
                    String filename = file.getName();
                    System.out.println("[DEBUG] Using filename: " + filename);

                    // URL encode the filename to safely transmit it, including spaces and special characters
                    String encodedFilename;
                    try {
                        encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
                        System.out.println("[DEBUG] Encoded filename: " + encodedFilename);
                    } catch (UnsupportedEncodingException e) {
                        System.out.println("[ERROR] Failed to encode filename: " + e.getMessage());
                        continue;
                    }

                    long fileSize = file.length();
                    System.out.println("[DEBUG] File size: " + fileSize + " bytes");

                    if (!filename.toLowerCase().endsWith(".txt")) {
                        System.out.println("[ERROR] Only .txt files are allowed for upload.");
                        continue;
                    }

                    // Create a new socket for the file transfer to avoid protocol mixing issues
                    try (Socket uploadSocket = new Socket(serverHost, serverPort)) {
                        // Configure upload socket
                        uploadSocket.setKeepAlive(true);
                        uploadSocket.setTcpNoDelay(true);
                        uploadSocket.setSoTimeout(60000); // 60 second timeout

                        System.out.println("[DEBUG] Created dedicated upload socket");

                        // Create streams for the upload socket
                        BufferedReader uploadReader = new BufferedReader(new InputStreamReader(uploadSocket.getInputStream(), StandardCharsets.UTF_8));
                        BufferedWriter uploadWriter = new BufferedWriter(new OutputStreamWriter(uploadSocket.getOutputStream(), StandardCharsets.UTF_8));
                        DataOutputStream uploadDataOut = new DataOutputStream(uploadSocket.getOutputStream());

                        // Skip the welcome message
                        uploadReader.readLine();

                        // Identify to the server
                        uploadWriter.write("CLIENT_ID " + clientName + "_upload\n");
                        uploadWriter.flush();

                        // Skip the available commands message
                        uploadReader.readLine();

                        System.out.println("[INFO] Preparing to upload: " + filename + " (" + fileSize + " bytes)");

                        // Send the UPLOAD command
                        uploadWriter.write("UPLOAD " + encodedFilename + "\n");
                        uploadWriter.flush();
                        System.out.println("[DEBUG] Sent command to server: UPLOAD " + encodedFilename);

                        // Send file size
                        uploadDataOut.writeLong(fileSize);
                        uploadDataOut.flush();
                        System.out.println("[DEBUG] Sent file size: " + fileSize);

                        // Calculate and send checksum
                        byte[] checksum = calculateChecksum(file);
                        uploadDataOut.writeInt(checksum.length);
                        uploadDataOut.write(checksum);
                        uploadDataOut.flush();
                        System.out.println("[DEBUG] Sent checksum of length: " + checksum.length + " bytes");

                        // Upload with progress reporting
                        try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
                            byte[] buffer = new byte[8192]; // Increased buffer size
                            int count;
                            long total = 0;
                            int lastPercentageReported = 0;

                            System.out.println("[DEBUG] Starting file data transfer");
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
                            System.out.println("[DEBUG] Total bytes sent: " + total);
                        }

                        // Wait for server response
                        try {
                            uploadSocket.setSoTimeout(10000); // 10 second timeout for response
                            String uploadResponse = uploadReader.readLine();
                            if (uploadResponse != null) {
                                System.out.println("[SERVER] " + uploadResponse);
                            } else {
                                System.out.println("[WARNING] No response received from server after upload");
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("[WARNING] No response received from server after upload (timeout)");
                        }

                        System.out.println("[INFO] Upload connection closed");
                    } catch (Exception e) {
                        System.out.println("[ERROR] Upload failed: " + e.getMessage());
                        e.printStackTrace();
                    }

                    // Use the main connection to verify the upload worked
                    try {
                        System.out.println("[INFO] Verifying upload with LIST command...");
                        writer.write("LIST\n");
                        writer.flush();

                        // Read LIST response
                        socket.setSoTimeout(5000); // 5 second timeout for LIST response
                        String line;
                        boolean fileFound = false;
                        try {
                            while ((line = reader.readLine()) != null) {
                                System.out.println("[SERVER] " + line);
                                if (line.contains(filename)) {
                                    fileFound = true;
                                }

                                if (line.isEmpty()) {
                                    break;
                                }

                                if (!reader.ready()) {
                                    break;
                                }
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("[WARNING] Timeout reading LIST response");
                        }

                        socket.setSoTimeout(60000); // Reset timeout

                        if (fileFound) {
                            System.out.println("[INFO] File upload confirmed: " + filename + " found on server");
                        } else {
                            System.out.println("[WARNING] Could not confirm if file was uploaded. Please try LIST command manually.");
                        }
                    } catch (Exception e) {
                        System.out.println("[ERROR] Error verifying upload: " + e.getMessage());
                    }

                } else if (parts[0].equalsIgnoreCase("DOWNLOAD") && parts.length == 2) {
                    String filename = parts[1];
                    System.out.println("[DEBUG] Processing DOWNLOAD command for: " + filename);

                    // URL encode the filename for safe transmission
                    String encodedFilename;
                    try {
                        encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
                        System.out.println("[DEBUG] Encoded filename: " + encodedFilename);
                    } catch (UnsupportedEncodingException e) {
                        System.out.println("[ERROR] Failed to encode filename: " + e.getMessage());
                        continue;
                    }

                    // Create a dedicated connection for downloading to avoid protocol mixing
                    try (Socket downloadSocket = new Socket(serverHost, serverPort)) {
                        // Configure download socket
                        downloadSocket.setKeepAlive(true);
                        downloadSocket.setTcpNoDelay(true);
                        downloadSocket.setSoTimeout(120000); // 2 minutes timeout

                        System.out.println("[DEBUG] Created dedicated download socket");

                        // Create streams for the download socket
                        BufferedReader downloadReader = new BufferedReader(new InputStreamReader(downloadSocket.getInputStream(), StandardCharsets.UTF_8));
                        BufferedWriter downloadWriter = new BufferedWriter(new OutputStreamWriter(downloadSocket.getOutputStream(), StandardCharsets.UTF_8));
                        DataInputStream downloadDataIn = new DataInputStream(downloadSocket.getInputStream());

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
                        System.out.println("[DEBUG] Sent DOWNLOAD command");

                        // Read file size
                        long fileSize = downloadDataIn.readLong();
                        System.out.println("[DEBUG] Received file size: " + fileSize + " bytes");

                        if (fileSize < 0) {
                            System.out.println("[ERROR] Server reported file not found: " + filename);
                            continue;
                        }

                        // Validate file size
                        if (fileSize > 10_000_000_000L) { // 10GB max as sanity check
                            System.out.println("[ERROR] Invalid file size reported: " + fileSize);
                            continue;
                        }

                        System.out.println("[INFO] Receiving file: " + filename + " (" + fileSize + " bytes)");

                        // Receive checksum
                        int checksumLength = downloadDataIn.readInt();
                        System.out.println("[DEBUG] Received checksum length: " + checksumLength);

                        if (checksumLength <= 0 || checksumLength > 64) { // SHA-256 is 32 bytes, SHA-512 is 64 bytes
                            System.out.println("[ERROR] Invalid checksum length: " + checksumLength);
                            continue;
                        }

                        byte[] expectedChecksum = new byte[checksumLength];
                        downloadDataIn.readFully(expectedChecksum);
                        System.out.println("[DEBUG] Received checksum data");

                        // Create downloads directory if it doesn't exist
                        File dir = new File("downloads");
                        if (!dir.exists()) {
                            boolean created = dir.mkdirs();
                            System.out.println("[DEBUG] Created downloads directory: " + created);
                        }

                        // Use a temporary file for downloading
                        String tempFileName = "temp_" + System.currentTimeMillis() + "_" + filename;
                        File tempFile = new File(dir, tempFileName);
                        File outFile = new File(dir, filename);
                        System.out.println("[DEBUG] Using temporary file: " + tempFile.getPath());

                        try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                            byte[] buffer = new byte[8192]; // Increased buffer size
                            long remaining = fileSize;
                            int count;
                            int lastPercentageReported = 0;

                            System.out.println("[DEBUG] Starting file data reception");
                            while (remaining > 0) {
                                // Carefully read with proper handling of partial reads
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
                            System.out.println("[DEBUG] File data reception complete");
                        }

                        // Verify checksum
                        System.out.println("[DEBUG] Calculating checksum of downloaded file");
                        byte[] actualChecksum = calculateChecksum(tempFile);
                        boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);
                        System.out.println("[DEBUG] Checksum match: " + checksumMatch);

                        if (!checksumMatch) {
                            System.out.println("[ERROR] Downloaded file is corrupted. Checksum verification failed.");
                            tempFile.delete(); // Delete corrupted file
                        } else {
                            // If checksum matches, rename temp file to final name
                            if (outFile.exists()) {
                                boolean deleted = outFile.delete(); // Delete any existing file with same name
                                System.out.println("[DEBUG] Deleted existing file: " + deleted);
                            }

                            boolean renamed = tempFile.renameTo(outFile);
                            System.out.println("[DEBUG] Renamed temp file to final: " + renamed);

                            if (renamed) {
                                System.out.println("[INFO] Downloaded " + filename + " to folder: downloads/");
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
                                System.out.println("[SERVER] " + downloadResponse);
                            }
                        } catch (SocketTimeoutException e) {
                            // It's okay if we don't get a response after download
                            System.out.println("[INFO] No server message received after download");
                        }

                        System.out.println("[INFO] Download completed");
                    } catch (Exception e) {
                        System.out.println("[ERROR] Download failed: " + e.getMessage());
                        e.printStackTrace();
                    }

                } else if (parts[0].equalsIgnoreCase("LIST")) {
                    try {
                        writer.write("LIST\n");
                        writer.flush();
                        System.out.println("[DEBUG] Sent LIST command");
                        System.out.println("[INFO] Requesting file list from server...");

                        // Set a shorter timeout for LIST command
                        socket.setSoTimeout(10000);
                        System.out.println("[DEBUG] Set socket timeout to 10 seconds for LIST");

                        try {
                            String line;
                            int lineCount = 0;

                            System.out.println("[DEBUG] Starting to read list response");
                            while ((lineCount < 100) && (line = reader.readLine()) != null) {
                                System.out.println("[SERVER] " + line);
                                lineCount++;

                                if (line.isEmpty()) {
                                    System.out.println("[DEBUG] Empty line received, ending list");
                                    break;
                                }

                                if (!reader.ready()) {
                                    System.out.println("[DEBUG] No more data ready in reader");
                                    break;
                                }
                            }

                            System.out.println("[DEBUG] Read " + lineCount + " lines from list response");
                            if (lineCount == 0) {
                                System.out.println("[INFO] No files found or empty response from server");
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("[WARNING] Server response timeout. Partial list may have been received.");
                            System.out.println("[DEBUG] Exception: " + e.getMessage());
                        } finally {
                            // Reset to normal timeout
                            socket.setSoTimeout(60000);
                            System.out.println("[DEBUG] Reset socket timeout to 60 seconds");
                        }
                    } catch (Exception e) {
                        System.out.println("[ERROR] LIST command failed: " + e.getMessage());
                    }

                } else if (parts[0].equalsIgnoreCase("VIEWLOGS")) {
                    try {
                        writer.write("VIEWLOGS\n");
                        writer.flush();
                        System.out.println("[DEBUG] Sent VIEWLOGS command");
                        System.out.println("[INFO] Requesting logs from server...");

                        // Set a shorter timeout for VIEWLOGS command
                        socket.setSoTimeout(10000);
                        System.out.println("[DEBUG] Set socket timeout to 10 seconds for VIEWLOGS");

                        try {
                            String line;
                            int lineCount = 0;

                            System.out.println("[DEBUG] Starting to read logs response");
                            while ((lineCount < 100) && (line = reader.readLine()) != null) {
                                System.out.println("[SERVER] " + line);
                                lineCount++;

                                if (line.isEmpty()) {
                                    System.out.println("[DEBUG] Empty line received, ending logs");
                                    break;
                                }

                                if (!reader.ready()) {
                                    System.out.println("[DEBUG] No more data ready in reader");
                                    break;
                                }
                            }

                            System.out.println("[DEBUG] Read " + lineCount + " lines from logs response");
                            if (lineCount == 0) {
                                System.out.println("[INFO] No logs found or empty response from server");
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("[WARNING] Server response timeout. Partial logs may have been received.");
                            System.out.println("[DEBUG] Exception: " + e.getMessage());
                        } finally {
                            // Reset to normal timeout
                            socket.setSoTimeout(60000);
                            System.out.println("[DEBUG] Reset socket timeout to 60 seconds");
                        }
                    } catch (Exception e) {
                        System.out.println("[ERROR] VIEWLOGS command failed: " + e.getMessage());
                    }

                } else if (parts[0].equalsIgnoreCase("EXIT")) {
                    try {
                        writer.write("EXIT\n");
                        writer.flush();
                        System.out.println("[DEBUG] Sent EXIT command");
                        System.out.println("Exiting client...");
                    } catch (Exception e) {
                        System.out.println("[ERROR] Error sending EXIT command: " + e.getMessage());
                    }
                    break;

                } else {
                    System.out.println("[ERROR] Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, VIEWLOGS, EXIT");
                }
            }

            // Clean up
            reader.close();
            writer.close();
            dataIn.close();
            dataOut.close();
            scanner.close();

        } catch (SocketTimeoutException e) {
            System.out.println("[ERROR] Connection timed out: " + e.getMessage());
            logger.log(Logger.Level.ERROR, "Client", "Connection timed out", e);
        } catch (SocketException e) {
            System.out.println("[ERROR] Connection lost: " + e.getMessage());
            logger.log(Logger.Level.ERROR, "Client", "Connection lost", e);
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "Client", "Connection error", e);
            System.out.println("[FATAL] Connection error: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for detailed debugging
        }
    }

    /**
     * Parses a command line, handling quoted arguments properly
     * @param command The command to parse
     * @return Array of command parts
     */
    private static String[] parseCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new String[0];
        }

        // Handle the case with no spaces
        if (!command.contains(" ")) {
            return new String[] { command };
        }

        String cmd = command.substring(0, command.indexOf(" ")).trim();
        String rest = command.substring(command.indexOf(" ")).trim();

        // Handle quoted arguments
        if (rest.startsWith("\"") && rest.endsWith("\"") && rest.length() > 1) {
            rest = rest.substring(1, rest.length() - 1);
        }

        return new String[] { cmd, rest };
    }

    private static byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }
}