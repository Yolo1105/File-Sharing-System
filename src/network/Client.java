package network;

import service.FileValidation;
import logs.Logger;
import constants.Constants;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import utils.IOUtils;
import config.Config;

public class Client {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final long MAX_FILE_SIZE = Config.getMaxFileSize();
    private static final String DOWNLOADS_DIR = Config.getDownloadsDir();
    private static volatile boolean connectionActive = true;
    private static final String PROMPT = "> ";

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", Constants.InfoMessages.INFO_STARTING);

        String serverHost = Config.getServerHost();
        int serverPort = Config.getServerPort();
        Socket socket = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;

        try {
            socket = new Socket(serverHost, serverPort);
            SocketHandler.configureStandardSocket(socket);

            System.out.println(String.format(Constants.InfoMessages.INFO_CONNECTED, serverHost, serverPort));
            logger.log(Logger.Level.INFO, "Client", String.format(Constants.InfoMessages.INFO_CONNECTED, serverHost, serverPort));

            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);

            try (Scanner scanner = new Scanner(System.in)) {
                System.out.print("Enter your client name: ");
                String clientName = scanner.nextLine().trim();
                writer.write(Config.Protocol.CLIENT_ID_PREFIX + clientName + "\n");
                writer.flush();

                String response = reader.readLine();
                System.out.println(response);

                Thread notificationThread = startNotificationListener(reader, socket);
                processUserCommands(scanner, writer, reader, serverHost, serverPort, clientName, socket);
            }
        } catch (SocketTimeoutException e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_TIMEOUT, e.getMessage()));
            logger.log(Logger.Level.ERROR, "Client", "Connection timed out", e);
        } catch (SocketException e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_CONNECTION_LOST, e.getMessage()));
            logger.log(Logger.Level.ERROR, "Client", "Connection lost", e);
        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "Client", "Connection error", e);
            System.out.println(String.format("Connection error: %s", e.getMessage()));
            e.printStackTrace();
        } finally {
            connectionActive = false;
            IOUtils.safeCloseAll(logger, reader, writer);
            IOUtils.safeClose(socket, "client socket", logger);

            System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            try {
                System.in.read();
            } catch (IOException e) {
            }
        }
    }

    private static Thread startNotificationListener(BufferedReader reader, final Socket socket) {
        Thread notificationThread = new Thread(() -> {
            try {
                while (connectionActive && !Thread.currentThread().isInterrupted()) {
                    try {
                        if (!socket.isConnected() || socket.isClosed()) {
                            System.out.println("\r\n" + Constants.InfoMessages.INFO_CONNECTION_CLOSED);
                            connectionActive = false;
                            break;
                        }

                        if (reader.ready()) {
                            String notification = reader.readLine();
                            if (notification == null) {
                                System.out.println("\r\n" + Constants.InfoMessages.INFO_CONNECTION_CLOSED);
                                connectionActive = false;
                                break;
                            }

                            if (notification.startsWith(Config.Protocol.NOTIFICATION_PREFIX)) {
                                System.out.print("\r");
                                System.out.println("\n[NOTIFICATION]" +
                                        notification.substring(Config.Protocol.NOTIFICATION_PREFIX.length()).trim());
                                System.out.print(PROMPT);
                            }
                        }

                        try {
                            socket.setSendBufferSize(socket.getSendBufferSize());
                        } catch (SocketException se) {
                            System.out.println("\r\n" + Constants.InfoMessages.INFO_CONNECTION_CLOSED);
                            connectionActive = false;
                            break;
                        }

                        Thread.sleep(200);
                    } catch (SocketException e) {
                        System.out.println("\r\n" + Constants.InfoMessages.INFO_CONNECTION_CLOSED);
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
                    case Config.Protocol.CMD_UPLOAD:
                        handleUploadCommand(command, serverHost, serverPort, clientName);
                        break;

                    case Config.Protocol.CMD_DOWNLOAD:
                        handleDownloadCommand(command, serverHost, serverPort, clientName);
                        break;

                    case Config.Protocol.CMD_LIST:
                        handleListCommand(writer, socket, reader);
                        break;

                    case Config.Protocol.CMD_LOGS:
                        handleLogsCommand(command, writer, socket, reader);
                        break;

                    case Config.Protocol.CMD_DELETE:
                        handleDeleteCommand(command, writer, socket, reader);
                        break;

                    default:
                        if (!isConnectionActive(socket)) {
                            break;
                        }
                        System.out.println(Constants.ErrorMessages.ERR_UNKNOWN_COMMAND);
                }

                if (!isConnectionActive(socket)) {
                    break;
                }
            } catch (IOException e) {
                connectionActive = false;
                System.out.println("Connection error: " + e.getMessage());
                System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
                break;
            }
        }
    }

    private static boolean isConnectionActive(Socket socket) {
        if (!connectionActive || socket.isClosed() || !socket.isConnected()) {
            System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            connectionActive = false;
            return false;
        }
        return true;
    }

    private static void handleUploadCommand(String command, String serverHost,
                                            int serverPort, String clientName) {
        String filePath = command.substring(Config.Protocol.CMD_UPLOAD.length()).trim();

        if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
            filePath = filePath.substring(1, filePath.length() - 1);
        }

        if (filePath.isEmpty()) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_MISSING_FILENAME, Config.Protocol.CMD_UPLOAD));
            System.out.println(String.format(Constants.InfoMessages.INFO_COMMAND_USAGE, Config.Protocol.CMD_UPLOAD, Config.Protocol.CMD_UPLOAD));
        } else {
            handleUpload(filePath, serverHost, serverPort, clientName);
        }
    }

    private static void handleDownloadCommand(String command, String serverHost,
                                              int serverPort, String clientName) {
        String filename = command.substring(Config.Protocol.CMD_DOWNLOAD.length()).trim();

        if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
        }

        if (filename.isEmpty()) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_MISSING_FILENAME, Config.Protocol.CMD_DOWNLOAD));
            System.out.println(String.format(Constants.InfoMessages.INFO_COMMAND_USAGE, Config.Protocol.CMD_DOWNLOAD, Config.Protocol.CMD_DOWNLOAD));
        } else {
            handleDownload(filename, serverHost, serverPort, clientName);
        }
    }

    private static void handleDeleteCommand(String command, BufferedWriter writer,
                                            Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            return;
        }

        String filename = command.substring(Config.Protocol.CMD_DELETE.length()).trim();

        if (filename.startsWith("\"") && filename.endsWith("\"")) {
            filename = filename.substring(1, filename.length() - 1);
        }

        if (filename.isEmpty()) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_MISSING_FILENAME, Config.Protocol.CMD_DELETE));
            System.out.println(String.format(Constants.InfoMessages.INFO_COMMAND_USAGE, Config.Protocol.CMD_DELETE, Config.Protocol.CMD_DELETE));
            return;
        }

        System.out.println(String.format("Processing delete request for: %s", filename));

        try {
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());

            writer.write(Config.Protocol.CMD_DELETE + " " + encodedFilename + "\n");
            writer.flush();

            String response = reader.readLine();
            if (response == null) {
                connectionActive = false;
                System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
                return;
            }
            System.out.println(response);
        } catch (IOException e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_DELETE_FAILED, e.getMessage()));
            if (!socket.isConnected() || socket.isClosed()) {
                connectionActive = false;
                System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            }
            throw e;
        }
    }

    private static void handleListCommand(BufferedWriter writer, Socket socket,
                                          BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            return;
        }

        writer.write(Config.Protocol.CMD_LIST + "\n");
        writer.flush();
        System.out.println("Requesting file list from server...");

        readServerResponse(socket, reader);
    }

    private static void handleLogsCommand(String command, BufferedWriter writer,
                                          Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            System.out.println(Constants.InfoMessages.INFO_CONNECTION_CLOSED);
            return;
        }

        int logCount = 10;
        String[] parts = command.split("\\s+", 2);
        if (parts.length > 1) {
            try {
                logCount = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
            }
        }

        writer.write(Config.Protocol.CMD_LOGS + " " + logCount + "\n");
        writer.flush();
        System.out.println(String.format("Requesting %d recent logs from server...", logCount));

        readServerResponse(socket, reader);
    }

    private static void handleUpload(String filePath, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(Constants.InfoMessages.INFO_PROCESSING, "UPLOAD", filePath));

        File file = new File(filePath);

        if (!file.exists()) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_FILE_NOT_FOUND, filePath));
            System.out.println("Please provide a valid file path.");
            return;
        }

        String filename = file.getName();

        if (FileValidation.isBlockedFileType(filename)) {
            System.out.println(Constants.ErrorMessages.ERR_BLOCKED_FILE_TYPE);
            return;
        }

        long fileSize = file.length();
        if (fileSize > MAX_FILE_SIZE) {
            System.out.println(Constants.ErrorMessages.ERR_FILE_TOO_LARGE);
            return;
        }

        System.out.println(String.format(Constants.InfoMessages.INFO_PREPARING, filename, fileSize));

        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        Socket uploadSocket = null;
        BufferedReader uploadReader = null;
        BufferedWriter uploadWriter = null;
        DataOutputStream uploadDataOut = null;

        try {
            uploadSocket = new Socket(serverHost, serverPort);
            SocketHandler.configureFileTransferSocket(uploadSocket);

            uploadReader = new BufferedReader(
                    new InputStreamReader(uploadSocket.getInputStream(), StandardCharsets.UTF_8));
            uploadWriter = new BufferedWriter(
                    new OutputStreamWriter(uploadSocket.getOutputStream(), StandardCharsets.UTF_8));
            uploadDataOut = new DataOutputStream(
                    new BufferedOutputStream(uploadSocket.getOutputStream(), BUFFER_SIZE));

            uploadReader.readLine();

            uploadWriter.write(Config.Protocol.CLIENT_ID_PREFIX + clientName + "_upload\n");
            uploadWriter.flush();

            uploadReader.readLine();

            uploadWriter.write(Config.Protocol.CMD_UPLOAD + " " + encodedFilename + "\n");
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
                        System.out.println(String.format(Constants.InfoMessages.INFO_PROGRESS, "Upload", currentPercentage));
                    }
                }
                uploadDataOut.flush();
                System.out.println(String.format(Constants.InfoMessages.INFO_COMPLETED, "File upload"));
            }

            try {
                uploadSocket.setSoTimeout(10000);
                String uploadResponse = uploadReader.readLine();
                if (uploadResponse != null) {
                    System.out.println(uploadResponse);
                }
                System.out.println(String.format(Constants.SuccessMessages.SUCCESS_UPLOAD, filename));
            } catch (SocketTimeoutException e) {
                System.out.println(String.format(Constants.SuccessMessages.SUCCESS_UPLOAD, filename));
            }

            System.out.println(String.format(Constants.InfoMessages.INFO_COMPLETED, "Upload"));
        } catch (Exception e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_UPLOAD_FAILED, e.getMessage()));
        } finally {
            IOUtils.safeCloseAll(logger, uploadReader, uploadWriter, uploadDataOut);
            IOUtils.safeClose(uploadSocket, "upload socket", logger);
        }
    }

    private static void handleDownload(String filename, String serverHost, int serverPort, String clientName) {
        System.out.println(String.format(Constants.InfoMessages.INFO_PROCESSING, "DOWNLOAD", filename));

        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_ENCODE_FILENAME, e.getMessage()));
            return;
        }

        Socket downloadSocket = null;
        BufferedReader downloadReader = null;
        BufferedWriter downloadWriter = null;
        DataInputStream downloadDataIn = null;

        try {
            downloadSocket = new Socket(serverHost, serverPort);
            SocketHandler.configureFileTransferSocket(downloadSocket);

            downloadReader = new BufferedReader(
                    new InputStreamReader(downloadSocket.getInputStream(), StandardCharsets.UTF_8));
            downloadWriter = new BufferedWriter(
                    new OutputStreamWriter(downloadSocket.getOutputStream(), StandardCharsets.UTF_8));
            downloadDataIn = new DataInputStream(
                    new BufferedInputStream(downloadSocket.getInputStream(), BUFFER_SIZE));

            downloadReader.readLine();

            downloadWriter.write(Config.Protocol.CLIENT_ID_PREFIX + clientName + "_download\n");
            downloadWriter.flush();

            downloadReader.readLine();

            System.out.println(String.format(Constants.InfoMessages.INFO_PROCESSING, "download", filename));

            downloadWriter.write(Config.Protocol.CMD_DOWNLOAD + " " + encodedFilename + "\n");
            downloadWriter.flush();

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long fileSize = downloadDataIn.readLong();

            if (fileSize < 0) {
                System.out.println(String.format(Constants.ErrorMessages.ERR_FILE_NOT_FOUND, filename));
                return;
            }

            if (fileSize > MAX_FILE_SIZE) {
                System.out.println(String.format(Constants.ErrorMessages.ERR_INVALID_FILESIZE, fileSize));
                return;
            }

            System.out.println(String.format("Receiving file: %s (%d bytes)", filename, fileSize));

            int checksumLength = downloadDataIn.readInt();

            if (checksumLength <= 0 || checksumLength > 64) {
                System.out.println(String.format(Constants.ErrorMessages.ERR_INVALID_CHECKSUM, checksumLength));
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
                        throw new IOException("Unexpected end of file during download");
                    }

                    fileOut.write(buffer, 0, count);
                    remaining -= count;

                    int currentPercentage = (int)(100 * (fileSize - remaining) / (double)fileSize);
                    if (currentPercentage >= lastPercentageReported + 10) {
                        lastPercentageReported = currentPercentage;
                        System.out.println(String.format(Constants.InfoMessages.INFO_PROGRESS, "Download", currentPercentage));
                    }
                }
                fileOut.flush();
            }

            byte[] actualChecksum = calculateChecksum(tempFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                System.out.println(Constants.ErrorMessages.ERR_CORRUPTED);
                IOUtils.safeDelete(tempFile, logger);
            } else {
                if (outFile.exists()) {
                    IOUtils.safeDelete(outFile, logger);
                }

                boolean renamed = tempFile.renameTo(outFile);

                if (renamed) {
                    System.out.println(String.format("Downloaded %s to folder: downloads/", filename));
                    System.out.println(String.format("File saved to: %s", outFile.getAbsolutePath()));
                } else {
                    System.out.println("Failed to save downloaded file");
                    IOUtils.safeDelete(tempFile, logger);
                }
            }

            try {
                downloadSocket.setSoTimeout(5000);
                String downloadResponse = downloadReader.readLine();
                if (downloadResponse != null) {
                    System.out.println(downloadResponse);
                }
            } catch (SocketTimeoutException e) {
            }

            System.out.println(String.format(Constants.InfoMessages.INFO_COMPLETED, "Download"));
        } catch (Exception e) {
            System.out.println(String.format(Constants.ErrorMessages.ERR_DOWNLOAD_FAILED, e.getMessage()));
        } finally {
            IOUtils.safeCloseAll(logger, downloadReader, downloadWriter, downloadDataIn);
            IOUtils.safeClose(downloadSocket, "download socket", logger);
        }
    }

    private static void readServerResponse(Socket socket, BufferedReader reader) throws IOException {
        if (!connectionActive || socket.isClosed()) {
            throw new IOException("Connection closed");
        }

        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(30000);

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

                if (line.equals(Config.Protocol.RESPONSE_END_MARKER)) {
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
            System.out.println(Constants.ErrorMessages.ERR_SERVER_TIMEOUT);
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