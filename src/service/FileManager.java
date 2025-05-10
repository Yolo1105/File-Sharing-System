package service;

import database.Database;
import logs.Logger;
import constants.Constants;

import java.io.*;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.sql.*;

import config.Config;

public class FileManager {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = Config.getBufferSize();

    // Maximum file size
    private static final long MAX_FILE_SIZE = Config.getMaxFileSize();

    public FileManager() {
        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILEMANAGER_INIT);
    }

    private String decodeFileName(String encodedFileName) {
        try {
            return URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_DECODE_FILENAME, e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_DECODE_FILENAME, e);
        }
    }

    private String processFileName(String fileName) {
        // Check for blocked file types
        if (FileValidation.isBlockedFileType(fileName)) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.InfoMessages.INFO_BLOCKED_FILE_REJECTED.formatted(fileName));
            throw new RuntimeException(Constants.ErrorMessages.ERR_BLOCKED_FILE_TYPE);
        }
        // Sanitize filename
        return FileValidation.sanitizeFileName(fileName);
    }

    private byte[] calculateChecksum(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    private boolean verifyChecksum(byte[] fileContent, byte[] expectedChecksum)
            throws NoSuchAlgorithmException {
        byte[] actualChecksum = calculateChecksum(fileContent);
        return MessageDigest.isEqual(expectedChecksum, actualChecksum);
    }

    public void receiveFile(String encodedFileName, DataInputStream dataIn) {
        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_RECEPTION_START.formatted(encodedFileName));

        String fileName = decodeFileName(encodedFileName);
        String sanitizedFileName = processFileName(fileName);

        try {
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_RECEIVING_FILE.formatted(fileName));

            // Read file size and validate
            long fileSize = dataIn.readLong();
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_SIZE.formatted(fileSize));

            if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_FILE_TOO_LARGE + ": " + fileSize);
                throw new IOException(Constants.ErrorMessages.ERR_FILE_TOO_LARGE + ": " + fileSize);
            }

            // Read and validate checksum
            int checksumLength = dataIn.readInt();
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_LENGTH.formatted(checksumLength));

            if (checksumLength <= 0 || checksumLength > 64) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_INVALID_CHECKSUM.formatted(checksumLength));
                throw new IOException(Constants.ErrorMessages.ERR_INVALID_CHECKSUM.formatted(checksumLength));
            }

            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_RECEIVED);

            // Read file data into buffer
            byte[] fileContent = readFileContent(dataIn, fileSize);

            // Verify checksum
            if (!verifyChecksum(fileContent, expectedChecksum)) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_CHECKSUM_VERIFICATION);
                throw new IOException(Constants.ErrorMessages.ERR_CHECKSUM_VERIFICATION);
            }
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_VERIFIED);

            // Save file to database
            saveFileToDatabase(sanitizedFileName, fileContent, fileSize, expectedChecksum);

            logger.log(Logger.Level.INFO, "FileManager",
                    Constants.SuccessMessages.SUCCESS_FILE_RECEIVED + ": " + sanitizedFileName);

        } catch (IOException | NoSuchAlgorithmException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_FILE_TRANSFER + ": " + fileName, e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_FILE_TRANSFER, e);
        }
    }

    private byte[] readFileContent(DataInputStream dataIn, long fileSize) throws IOException {
        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = fileSize;
        int count;
        long lastLoggedProgress = 0;

        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_START_FILE_TRANSFER);

        while (remaining > 0 &&
                (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
            fileBuffer.write(buffer, 0, count);
            remaining -= count;

            // Log progress less frequently for better performance
            if (fileSize > 1000000 && (fileSize - remaining) - lastLoggedProgress > fileSize / 5) {
                lastLoggedProgress = fileSize - remaining;
                int progress = (int)((fileSize - remaining) * 100 / fileSize);
                logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_RECEIVE_PROGRESS.formatted(progress));
            }
        }

        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_TRANSFER_COMPLETE);

        return fileBuffer.toByteArray();
    }

    private void saveFileToDatabase(String fileName, byte[] content, long fileSize, byte[] checksum)
            throws SQLException {
        Database.executeWithConnection(conn -> {
            try {
                // First try to delete if file exists
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM files WHERE filename = ?")) {
                    pstmt.setString(1, fileName);
                    pstmt.executeUpdate();
                }

                // Now insert the new file
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO files (filename, content, file_size, checksum) VALUES (?, ?, ?, ?)")) {
                    pstmt.setString(1, fileName);
                    pstmt.setBytes(2, content);
                    pstmt.setLong(3, fileSize);
                    pstmt.setBytes(4, checksum);
                    pstmt.executeUpdate();

                    logger.log(Logger.Level.INFO, "FileManager", Constants.SuccessMessages.SUCCESS_FILE_SAVED + ": " + fileName);
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, "FileManager");
    }

    public void sendFile(String encodedFileName, OutputStream outputStream) {
        String fileName = decodeFileName(encodedFileName);

        try {
            // Process filename (check blocked types and sanitize)
            String sanitizedFileName;
            try {
                sanitizedFileName = processFileName(fileName);
            } catch (RuntimeException e) {
                // Handle blocked file type case
                logger.log(Logger.Level.ERROR, "FileManager", Constants.InfoMessages.INFO_BLOCKED_DOWNLOAD_REJECTED.formatted(fileName));
                DataOutputStream dataOut = new DataOutputStream(outputStream);
                dataOut.writeLong(-1);  // Signal rejection
                dataOut.flush();
                return;
            }

            // Use buffered streams for better performance
            DataOutputStream dataOut = new DataOutputStream(
                    new BufferedOutputStream(outputStream, BUFFER_SIZE));

            // Retrieve file from database
            FileData fileData = getFileFromDatabase(sanitizedFileName);

            if (fileData == null) {
                logger.log(Logger.Level.WARNING, "FileManager", Constants.ErrorMessages.ERR_FILE_NOT_FOUND_DB + ": " + sanitizedFileName);
                dataOut.writeLong(-1); // Indicate file not found
                dataOut.flush();
                return;
            }

            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_SENDING_FILE.formatted(sanitizedFileName));

            // Send file size
            dataOut.writeLong(fileData.fileSize);
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_SENDING_FILE_SIZE.formatted(fileData.fileSize));

            // Send checksum
            dataOut.writeInt(fileData.checksum.length);
            dataOut.write(fileData.checksum);
            logger.log(Logger.Level.INFO, "FileManager",
                    Constants.InfoMessages.INFO_SENDING_CHECKSUM.formatted(fileData.checksum.length));
            dataOut.flush();

            // Send file contents
            dataOut.write(fileData.content);
            dataOut.flush();

            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_SENT.formatted(fileName));

        } catch (IOException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_RETRIEVE_FAILED.formatted(fileName), e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_RETRIEVE_FAILED.formatted(fileName), e);
        }
    }

    public boolean deleteFile(String encodedFileName) {
        String fileName = decodeFileName(encodedFileName);
        String sanitizedFileName = FileValidation.sanitizeFileName(fileName);

        return Database.queryWithConnection(conn -> {
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM files WHERE filename = ?")) {
                    pstmt.setString(1, sanitizedFileName);
                    int rowsAffected = pstmt.executeUpdate();

                    boolean deleted = rowsAffected > 0;
                    if (deleted) {
                        logger.log(Logger.Level.INFO, "FileManager", Constants.SuccessMessages.SUCCESS_FILE_DELETED + ": " + sanitizedFileName);
                    } else {
                        logger.log(Logger.Level.WARNING, "FileManager", Constants.ErrorMessages.ERR_FILE_NOT_FOUND_DB + ": " + sanitizedFileName);
                    }
                    return deleted;
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, false, "FileManager");
    }

    private static class FileData {
        byte[] content;
        long fileSize;
        byte[] checksum;

        FileData(byte[] content, long fileSize, byte[] checksum) {
            this.content = content;
            this.fileSize = fileSize;
            this.checksum = checksum;
        }
    }

    private FileData getFileFromDatabase(String fileName) throws SQLException {
        return Database.queryWithConnection(conn -> {
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT content, file_size, checksum FROM files WHERE filename = ?")) {
                    pstmt.setString(1, fileName);

                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            byte[] content = rs.getBytes("content");
                            long fileSize = rs.getLong("file_size");
                            byte[] checksum = rs.getBytes("checksum");

                            return new FileData(content, fileSize, checksum);
                        }
                        return null; // File not found
                    }
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, null, "FileManager");
    }

    public String listFiles() {
        return Database.queryWithConnection(conn -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(Constants.InfoMessages.INFO_FILE_LIST_HEADER).append('\n');

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT filename, file_size FROM files ORDER BY filename")) {

                    try (ResultSet rs = pstmt.executeQuery()) {
                        boolean hasFiles = false;

                        while (rs.next()) {
                            hasFiles = true;
                            String fileName = rs.getString("filename");
                            long fileSize = rs.getLong("file_size");

                            sb.append(Constants.InfoMessages.INFO_FILE_LIST_ENTRY.formatted(fileName, fileSize)).append('\n');
                        }

                        if (!hasFiles) {
                            sb.append(Constants.InfoMessages.INFO_NO_FILES).append('\n');
                        }
                    }
                }

                return sb.toString();
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, Constants.InfoMessages.INFO_FILE_LIST_ERROR, "FileManager");
    }
}