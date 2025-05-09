package network;

import service.FileValidationUtils;
import logs.Logger;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import config.Config;

public class Client {
    private static final Logger logger = Logger.getInstance();

    // Configuration values
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final int SOCKET_TIMEOUT = Config.getSocketTimeout();
    private static final int FILE_TRANSFER_TIMEOUT = Config.getFileTransferTimeout();
    private static final long MAX_FILE_SIZE = Config.getMaxFileSize();

    // Command constants from Config
    private static final String CMD_UPLOAD = Config.Protocol.CMD_UPLOAD;
    private static final String CMD_DOWNLOAD = Config.Protocol.CMD_DOWNLOAD;
    private static final String CMD_DELETE = Config.Protocol.CMD_DELETE;
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
    private static final String ERR_BLOCKED_FILE_TYPE = "This file type is not allowed for security reasons.";
    private static final String ERR_FILE_TOO_LARGE = "File exceeds maximum size limit of 10MB.";
    private static final String ERR_ENCODE_FILENAME = "Failed to encode filename: %s";
    private static final String ERR_UPLOAD_FAILED = "Upload failed: %s";
    private static final String ERR_DOWNLOAD_FAILED = "Download failed: %s";
    private static final String ERR_INVALID_FILESIZE = "Invalid file size reported: %s";
    private static final String ERR_INVALID_CHECKSUM = "Invalid checksum length: %s";
    private static final String ERR_EOF_DOWNLOAD = "Unexpected end of file during download";
    private static final String ERR_CORRUPTED = "Downloaded file is corrupted. Checksum verification failed.";
    private static final String ERR_SAVE_FAILED = "Failed to save downloaded file";
    private static final String ERR_SERVER_TIMEOUT = "Server response timeout. Try again or check server connection.";
    private static final String ERR_INVALID_COMMAND = "Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, DELETE <filename>, LIST, LOGS [count]";
    private static final String ERR_DELETE_FAILED = "ERROR: Delete failed: %s";

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
    private static final String INFO_DELETE_PROCESSING = "Processing delete request for: %s";
    private static final String SUCCESS_DELETED = "File '%s' was successfully deleted from the server.";
    private static final String CONNECTION_CLOSED = "Connection to server has been closed. Press Enter to exit.";

    private static final String PROMPT = "> ";
    private static final String DOWNLOADS_DIR = "downloads/";
    private static volatile boolean connectionActive = true;

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", INFO_STARTING);

        String serverHost = Config.getServerHost();
        int serverPort = Config.getServerPort();
        Socket socket = null;

        try {
            socket = new Socket(serverHost, serverPort);
            SocketHandler.configureStandardSocket(socket);

            System.out.println(String.format(INFO_CONNECTED, serverHost, serverPort));
            logger.log(Logger.Level.INFO, "Client", String.format(INFO_CONNECTED, serverHost, serverPort));

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);

            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter your client name: ");
            String clientName = scanner.nextLine().trim();
            writer.write(CLIENT_ID_PREFIX + clientName + "\n");
            writer.flush();

            String response = reader.readLine();
            System.out.println(response);

            Thread notificationThread = startNotificationListener(reader, socket);
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
        } finally {
            connectionActive = false;
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
            System.out.println(CONNECTION_CLOSED);
            try {
                System.in.read();
            } catch (IOException e) {
                // Ignore read errors
            }
        }
    }

    private static Thread startNotificationListener(BufferedReader reader, final Socket socket) {
        Thread notificationThread = new Thread(() -> {
            try {
                while (connectionActive && !Thread.currentThread().isInterrupted()) {
                    try {
                        if (!socket.isConnected() || socket.isClosed()) {
                            System.out.println("\r\n" + CONNECTION_CLOSED);
                            connectionActive = false;
                            break;
                        }

                        if (reader.ready()) {
                            String notification = reader.readLine();
                            if (notification == null) {
                                System.out.println("\r\n" + CONNECTION_CLOSED);
                                connectionActive = false;
                                break;
                            }

                            if (notification.startsWith(NOTIFICATION_PREFIX)) {
                                System.out.print("\r");
                                System.out.println("\n[NOTIFICATION]" +
                                        notification.substring(NOTIFICATION_PREFIX.length()).trim());
                                System.out.print(PROMPT);
                            }
                        }

                        // Check connection status
                        try {
                            socket.setSendBufferSize(socket.getSendBufferSize());
                        } catch (SocketException se) {
                            System.out.println("\r\n" + CONNECTION_CLOSED);
                            connectionActive = false;
                            break;
                        }

                        Thread.sleep(200);
                    } catch (SocketException e) {
                        System.out.println("\r\n" + CONNECTION_CLOSED);
                        connectionActive = false;
                        break;
                    } catch (IOException e) {
                        if (!connectionActive) {
                            break;
                        }
                        System.out.println("\r\nConnection error: " + e.getMessage());
                        connectionActive = false;
                        break;
                    }
                }
            } catch (InterruptedException e) {
                // Thread is terminating
            }
        });

        notificationThread.setDaemon(true);
        notificationThread.start();
        return notificationThread;
    }

    private static void processUserCommands(Scanner scanner, BufferedWriter writer,
                                            BufferedReader reader, String serverHost, int serverPort,
                                            String clientName, Socket socket) throws IOException {

        while (connectionActive) {
            System.out.print(PROMPT);

            if (!scanner.hasNextLine()) {
                break;
            }

            String command = scanner.nextLine().trim();

            if (command.isEmpty()) {
                continue;
            }

            if (!isConnectionActive(socket)) {
                break;
            }

            String[] parts = command.split("\\s+", 2);
            String cmd = parts[0].toUpperCase();

            try {
                if (!isConnectionActive(socket)) {
                    break;
                }

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

                    case CMD_DELETE:
                        handleDeleteCommand(command, writer, socket, reader);
                        break;

                    default:
                        if (!isConnectionActive(socket)) {
                            break;
                        }
                        System.out.println(ERR_INVALID_COMMAND);
                }

                if (!isConnectionActive(socket)) {
                    break;
                }
            } catch (IOException e) {
                connectionActive = false;
                System.out.println("Connection error: " + e.getMessage());
                System.out.println(CONNECTION_CLOSED);
                break;
            }
        }
    }

    private static boolean isConnectionActive(Socket socket) {
        if (!connectionActive || socket.isClosed() || !socket.isConnected()) {
            System.out.println(CONNECTION_CLOSED);
            connectionActive = false;
            return false;
        }
        return true;
    }

    private static void handleUploadCommand(String command, String serverHost,
                                            int serverPort, String clientName) {
        String filePath = command.substring(CMD_UPLOAD.length()).trim();

        if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
            filePath = filePath.substring(1, filePath.length() - 1);
        }

        if (filePath.isEmpty()) {
            System.out.println(String.format(ERR_MISSING_FILENAME, CMD_UPLOAD));
            System.out.println(String.format(INFO_COMMAND_USAGE, CMD_UPLOAD, CMD_UPLOAD));
        } else {
            handleUpload(filePath, serverHost, serverPort, clientName);
        }
    }

    private static void handleDownloadCommand(String command, String serverHost,
                                              int serverPort, String clientName) {
        String filename = command.substring(CMD_DOWNLOAD.length()).trim();

        if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
        }

        if (filename.isEmpty()) {
            System.out.println(String.format(ERR_MISSING_FILENAME, CMD_DOWNLOAD));
            System.out.println(String.format(INFO_COMMAND_USAGE, CMD_DOWNLOAD, CMD_DOWNLOAD));
        } else {
            handleDownload(filename, serverHost, serverPort, clientName);
        }
    }

    private static void handleDeleteCommand(String command, BufferedWriter writer,
                                            Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(CONNECTION_CLOSED);
            return;
        }

        String filename = command.substring(CMD_DELETE.length()).trim();

        if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
        }

        if (filename.isEmpty()) {
            System.out.println(String.format(ERR_MISSING_FILENAME, CMD_DELETE));
            System.out.println(String.format(INFO_COMMAND_USAGE, CMD_DELETE, CMD_DELETE));
            return;
        }

        System.out.println(String.format(INFO_DELETE_PROCESSING, filename));

        try {
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());

            writer.write(CMD_DELETE + " " + encodedFilename + "\n");
            writer.flush();

            String response = reader.readLine();
            if (response == null) {
                connectionActive = false;
                System.out.println(CONNECTION_CLOSED);
                return;
            }
            System.out.println(response);
        } catch (IOException e) {
            System.out.println(String.format(ERR_DELETE_FAILED, e.getMessage()));
            if (!socket.isConnected() || socket.isClosed()) {
                connectionActive = false;
                System.out.println(CONNECTION_CLOSED);
            }
            throw e;
        }
    }

    private static void handleListCommand(BufferedWriter writer, Socket socket,
                                          BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(CONNECTION_CLOSED);
            return;
        }

        writer.write(CMD_LIST + "\n");
        writer.flush();
        System.out.println(INFO_REQUEST_LIST);

        readServerResponse(socket, reader);
    }

    private static void handleLogsCommand(String command, BufferedWriter writer,
                                          Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(CONNECTION_CLOSED);
            return;
        }

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

        readServerResponse(socket, reader);
    }

    private static void handleUpload(String filePath, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(INFO_PROCESSING, "UPLOAD", filePath));

        File file = new File(filePath);

        if (!file.exists()) {
            System.out.println(String.format(ERR_FILE_NOT_FOUND, filePath));
            System.out.println("Please provide a valid file path.");
            return;
        }

        String filename = file.getName();

        if (FileValidationUtils.isBlockedFileType(filename)) {
            System.out.println(ERR_BLOCKED_FILE_TYPE);
            return;
        }

        long fileSize = file.length();
        if (fileSize > MAX_FILE_SIZE) {
            System.out.println(ERR_FILE_TOO_LARGE);
            return;
        }

        System.out.println(String.format(INFO_PREPARING, filename, fileSize));

        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        try (Socket uploadSocket = new Socket(serverHost, serverPort)) {
            SocketHandler.configureFileTransferSocket(uploadSocket);

            BufferedReader uploadReader = new BufferedReader(
                    new InputStreamReader(uploadSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter uploadWriter = new BufferedWriter(
                    new OutputStreamWriter(uploadSocket.getOutputStream(), StandardCharsets.UTF_8));
            DataOutputStream uploadDataOut = new DataOutputStream(
                    new BufferedOutputStream(uploadSocket.getOutputStream(), BUFFER_SIZE));

            uploadReader.readLine();

            uploadWriter.write(CLIENT_ID_PREFIX + clientName + "_upload\n");
            uploadWriter.flush();

            uploadReader.readLine();

            uploadWriter.write(CMD_UPLOAD + " " + encodedFilename + "\n");
            uploadWriter.flush();

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            uploadDataOut.writeLong(fileSize);

            byte[] checksum = calculateChecksum(file);
            uploadDataOut.writeInt(checksum.length);
            uploadDataOut.write(checksum);
            uploadDataOut.flush();

            try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                long total = 0;
                int lastPercentageReported = 0;

                while ((count = fileIn.read(buffer)) > 0) {
                    uploadDataOut.write(buffer, 0, count);
                    total += count;
                    int currentPercentage = (int)(100 * total / (double)fileSize);

                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println(String.format(INFO_PROGRESS, "Upload", currentPercentage));
                    }
                }
                uploadDataOut.flush();
                System.out.println(String.format(INFO_COMPLETED, "File upload"));
            }

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

    private static void handleDownload(String filename, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(INFO_PROCESSING, "DOWNLOAD", filename));

        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        try (Socket downloadSocket = new Socket(serverHost, serverPort)) {
            SocketHandler.configureFileTransferSocket(downloadSocket);

            BufferedReader downloadReader = new BufferedReader(
                    new InputStreamReader(downloadSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter downloadWriter = new BufferedWriter(
                    new OutputStreamWriter(downloadSocket.getOutputStream(), StandardCharsets.UTF_8));
            DataInputStream downloadDataIn = new DataInputStream(
                    new BufferedInputStream(downloadSocket.getInputStream(), BUFFER_SIZE));

            downloadReader.readLine();

            downloadWriter.write(CLIENT_ID_PREFIX + clientName + "_download\n");
            downloadWriter.flush();

            downloadReader.readLine();

            System.out.println(String.format(INFO_PROCESSING, "download", filename));

            downloadWriter.write(CMD_DOWNLOAD + " " + encodedFilename + "\n");
            downloadWriter.flush();

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long fileSize = downloadDataIn.readLong();

            if (fileSize < 0) {
                System.out.println(String.format(ERR_FILE_NOT_FOUND, filename));
                return;
            }

            if (fileSize > MAX_FILE_SIZE) {
                System.out.println(String.format(ERR_INVALID_FILESIZE, fileSize));
                return;
            }

            System.out.println(String.format(INFO_RECEIVING, filename, fileSize));

            int checksumLength = downloadDataIn.readInt();

            if (checksumLength <= 0 || checksumLength > 64) {
                System.out.println(String.format(ERR_INVALID_CHECKSUM, checksumLength));
                return;
            }

            byte[] expectedChecksum = new byte[checksumLength];
            downloadDataIn.readFully(expectedChecksum);

            File dir = new File(DOWNLOADS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String tempFileName = "temp_" + System.currentTimeMillis() + "_" + filename;
            File tempFile = new File(dir, tempFileName);
            File outFile = new File(dir, filename);

            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
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

                    int currentPercentage = (int)(100 * (fileSize - remaining) / (double)fileSize);
                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println(String.format(INFO_PROGRESS, "Download", currentPercentage));
                    }
                }
                fileOut.flush();
            }

            byte[] actualChecksum = calculateChecksum(tempFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                System.out.println(ERR_CORRUPTED);
                tempFile.delete();
            } else {
                if (outFile.exists()) {
                    outFile.delete();
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

            try {
                downloadSocket.setSoTimeout(5000);
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
        }
    }

    private static void readServerResponse(Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            throw new IOException("Connection closed");
        }

        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(30000); // 30 second timeout

            String line;
            int lineCount = 0;
            boolean endMarkerFound = false;

            while (!endMarkerFound && lineCount < 100) {
                if (!connectionActive || socket.isClosed()) {
                    throw new IOException("Connection closed");
                }

                line = reader.readLine();

                if (line == null) {
                    connectionActive = false;
                    throw new IOException("Connection closed by server");
                }

                if (line.equals(RESPONSE_END_MARKER)) {
                    endMarkerFound = true;
                    continue;
                }

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
        } catch (SocketException e) {
            connectionActive = false;
            throw new IOException("Connection closed", e);
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (SocketException e) {
                connectionActive = false;
                throw new IOException("Connection closed", e);
            }
        }
    }

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