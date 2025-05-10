package network;

import config.Config;
import constants.Constants;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import logs.DBLogger;
import logs.Logger;
import service.FileManager;
import service.FileValidation;
import utils.IOProcessor;
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
    private static final int BUFFER_SIZE = Config.getBufferSize();

    private static final String CMD_UPLOAD = Config.Protocol.CMD_UPLOAD;
    private static final String CMD_DOWNLOAD = Config.Protocol.CMD_DOWNLOAD;
    private static final String CMD_DELETE = Config.Protocol.CMD_DELETE;
    private static final String CMD_LIST = Config.Protocol.CMD_LIST;
    private static final String CMD_LOGS = Config.Protocol.CMD_LOGS;
    private static final String CLIENT_ID_PREFIX = Config.Protocol.CLIENT_ID_PREFIX;
    private static final String RESPONSE_END_MARKER = Config.Protocol.RESPONSE_END_MARKER;

    private static final String ERR_MISSING_FILENAME = Constants.ErrorMessages.ERR_MISSING_FILENAME;
    private static final String ERR_BLOCKED_FILE_TYPE = Constants.ErrorMessages.ERR_BLOCKED_FILE_TYPE;
    private static final String ERR_UPLOAD_FAILED = Constants.ErrorMessages.ERR_UPLOAD_FAILED;
    private static final String ERR_DOWNLOAD_FAILED = Constants.ErrorMessages.ERR_DOWNLOAD_FAILED;
    private static final String ERR_UNKNOWN_COMMAND = Constants.ErrorMessages.ERR_UNKNOWN_COMMAND;
    private static final String ERR_COMMAND_FAILED = Constants.ErrorMessages.ERR_COMMAND_FAILED;
    private static final String ERR_DELETE_FAILED = Constants.ErrorMessages.ERR_DELETE_FAILED;

    private static final String SUCCESS_UPLOAD = Constants.SuccessMessages.SUCCESS_UPLOAD;
    private static final String SUCCESS_DOWNLOAD = Constants.SuccessMessages.SUCCESS_DOWNLOAD;
    private static final String SUCCESS_DELETE = Constants.SuccessMessages.SUCCESS_DELETE;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        logger.log(Logger.Level.INFO, "ClientHandler", "New client connected: " + socket.getRemoteSocketAddress());

        try {
            SocketHandler.SocketSetup(socket);
            createStreams();

            writer.write("Welcome to the File Sharing Server. Please enter you name to start: \n");
            writer.flush();

            String initial = reader.readLine();
            if (!clientIdentificationCheck(initial)) {
                return; 
            }
            processCommands();
        } catch (SocketException e) {
            logger.log(Logger.Level.INFO, "ClientHandler", "Connection closed by client: " + clientName);
        } catch (IOException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Connection lost with " + clientName, e);
        } finally {
            cleanup();
        }
    }

    private void createStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
        dataOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
    }

    private boolean clientIdentificationCheck(String identificationMessage) throws IOException {
        if (identificationMessage != null && identificationMessage.startsWith(CLIENT_ID_PREFIX)) {
            String[] parts = identificationMessage.split("\\s+", 2);
            if (parts.length == 2) {
                clientName = parts[1].trim();
                if (!Config.ServiceConnectionCheck(clientName)) {
                    broadcaster.register(clientName, writer);
                }
                writer.write("Available commands: UPLOAD <filename>, DOWNLOAD <filename>, DELETE <filename>, LIST, LOGS [count]\n");
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

    private void processCommands() throws IOException {
        String line;
        while (running && (line = reader.readLine()) != null) {
            logger.log(Logger.Level.INFO, "ClientHandler", clientName + " issued command: " + line);

            String command;
            String remainder = "";

            int spaceIndex = line.indexOf(' ');
            if (spaceIndex == -1) {
                command = line.toUpperCase();
            } else {
                command = line.substring(0, spaceIndex).toUpperCase();
                remainder = line.substring(spaceIndex + 1);
            }

            try {
                switch (command) {
                    case CMD_UPLOAD:
                        upload(remainder);
                        break;

                    case CMD_DOWNLOAD:
                        download(remainder);
                        break;

                    case CMD_LIST:
                        handleList();
                        break;

                    case CMD_LOGS:
                        handleLogs(remainder);
                        break;

                    case CMD_DELETE:
                        handleDelete(remainder);
                        break;

                    default:
                        writer.write(ERR_UNKNOWN_COMMAND + "\n");
                        writer.flush();
                        break;
                }
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to processing command: " + command, e);
                try {
                    writer.write(String.format(ERR_COMMAND_FAILED, e.getMessage()) + "\n");
                    writer.flush();
                } catch (IOException ioe) {
                    
                    logger.log(Logger.Level.ERROR, "ClientHandler",
                            "Failed to send error message, connection may be lost", ioe);
                    break;
                }
            }
        }
    }

    private void upload(String uploadFilename) throws IOException {
        if (uploadFilename == null || uploadFilename.trim().isEmpty()) {
            writer.write(String.format(ERR_MISSING_FILENAME, "UPLOAD") + "\n");
            writer.flush();
            return;
        }

        if (FileValidation.checkBlockedFile(uploadFilename)) {
            writer.write(ERR_BLOCKED_FILE_TYPE + "\n");
            writer.flush();
            return;
        }

        try {
            SocketHandler.FileTransferSocket(socket);
            fileManager.receiveFile(uploadFilename, dataInputStream);
            DBLogger.log(clientName, "UPLOAD", uploadFilename);

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            dataOutputStream.flush();
            writer.write(SUCCESS_UPLOAD + "\n");
            writer.flush();

            String baseClientName = getClientName();
            broadcaster.broadcastFileUpload(baseClientName, uploadFilename);

            SocketHandler.SocketSetup(socket);
        } catch (RuntimeException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Upload failed: " + e.getMessage(), e);
            writer.write(String.format(ERR_UPLOAD_FAILED, e.getMessage()) + "\n");
            writer.flush();

            SocketHandler.SocketSetup(socket);
        }
    }

    private void download(String downloadFilename) throws IOException {
        if (downloadFilename == null || downloadFilename.trim().isEmpty()) {
            writer.write(String.format(ERR_MISSING_FILENAME, "DOWNLOAD") + "\n");
            writer.flush();
            return;
        }

        if (FileValidation.checkBlockedFile(downloadFilename)) {
            writer.write(ERR_BLOCKED_FILE_TYPE + "\n");
            writer.flush();
            return;
        }

        try {
            SocketHandler.FileTransferSocket(socket);
            fileManager.sendFile(downloadFilename, socket.getOutputStream());
            DBLogger.log(clientName, "DOWNLOAD", downloadFilename);

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            writer.write(SUCCESS_DOWNLOAD + "\n");
            writer.flush();

            String baseClientName = getClientName();
            broadcaster.broadcastFileDownload(baseClientName, downloadFilename);
            SocketHandler.SocketSetup(socket);
        } catch (RuntimeException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to sending file: " + e.getMessage(), e);
            writer.write(String.format(ERR_DOWNLOAD_FAILED, e.getMessage()) + "\n");
            writer.flush();
            SocketHandler.SocketSetup(socket);
        }
    }

    private void handleDelete(String deleteFilename) throws IOException {
        if (deleteFilename == null || deleteFilename.trim().isEmpty()) {
            writer.write(String.format(ERR_MISSING_FILENAME, "DELETE") + "\n");
            writer.flush();
            return;
        }

        try {
            String decodedFilename = URLDecoder.decode(deleteFilename, StandardCharsets.UTF_8.toString());
            boolean deleted = fileManager.deleteFile(decodedFilename);

            if (deleted) {
                DBLogger.log(clientName, "DELETE", decodedFilename);
                writer.write(String.format(SUCCESS_DELETE, decodedFilename) + "\n");
                writer.flush();
                String baseClientName = getClientName();
                broadcaster.broadcastFileDeletion(baseClientName, decodedFilename);
            } else {
                
                writer.write(String.format(ERR_DELETE_FAILED, "File not found: " + decodedFilename) + "\n");
                writer.flush();
            }
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Failed to decode filename: " + e.getMessage(), e);
            writer.write(String.format(ERR_DELETE_FAILED, "Invalid filename encoding") + "\n");
            writer.flush();
        } catch (RuntimeException e) {
            logger.log(Logger.Level.ERROR, "ClientHandler", "Delete failed: " + e.getMessage(), e);
            writer.write(String.format(ERR_DELETE_FAILED, e.getMessage()) + "\n");
            writer.flush();
        }
    }

    private void handleList() throws IOException {
        String fileList = fileManager.listFiles();
        if (fileList.contains("No files available")) {
            writer.write("Available files:\nNo files available on the server.\n" + RESPONSE_END_MARKER + "\n");
        } else {
            
            writer.write(fileList.trim() + "\n" + RESPONSE_END_MARKER + "\n");
        }

        writer.flush();
    }

    private void handleLogs(String params) throws IOException {
        int logCount = 10;
        if (params != null && !params.trim().isEmpty()) {
            try {
                logCount = Integer.parseInt(params.trim());
                
                if (logCount < 1) logCount = 1;
                if (logCount > 100) logCount = 100;
            } catch (NumberFormatException e) {
                
            }
        }

        String logs = DBLogger.getRecentLogs(logCount);
        writer.write(logs + RESPONSE_END_MARKER + "\n");
        writer.flush();
    }

    private String getClientName() {
        if (clientName.contains("_")) {
            return clientName.substring(0, clientName.indexOf("_"));
        }
        return clientName;
    }

    private void cleanup() {
        IOProcessor.closeCheckAll(logger, reader, writer, dataInputStream, dataOutputStream);
        if (socket != null && !socket.isClosed()) {
            IOProcessor.closeCheck(socket, "client socket", logger);
        }

        if (clientName != null && !clientName.isEmpty() && !Config.ServiceConnectionCheck(clientName)) {
            broadcaster.unregister(clientName);
        }

        logger.log(Logger.Level.INFO, "ClientHandler", "All resources cleaned up for client: " + clientName);
    }
}