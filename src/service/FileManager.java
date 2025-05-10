package service;

import config.Config;
import constants.Constants;
import database.Database;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import logs.Logger;

public class FileManager {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final long MAX_FILE_SIZE = Config.getMaxFileSize();

    public FileManager() {
        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILEMANAGER_INIT);
    }

    private String decodeFile(String encodedFile) {
        try {
            return URLDecoder.decode(encodedFile, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_DECODE_FILENAME, e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_DECODE_FILENAME, e);
        }
    }

    private String processFile(String inputFile) {
        if (FileValidation.checkBlockedFile(inputFile)) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.InfoMessages.INFO_BLOCKED_FILE_REJECTED.formatted(inputFile));
            throw new RuntimeException(Constants.ErrorMessages.ERR_BLOCKED_FILE_TYPE);
        }
        return FileValidation.sanitizeFile(inputFile);
    }

    private byte[] processChecksum(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    private boolean verifyChecksum(byte[] fileContent, byte[] expectedChecksum) throws NoSuchAlgorithmException {
        byte[] actualChecksum = processChecksum(fileContent);
        return MessageDigest.isEqual(expectedChecksum, actualChecksum);
    }

    public void receiveFile(String encodedFile, DataInputStream dataIn) {
        String inputFile = decodeFile(encodedFile);
        String sanitizedFile = processFile(inputFile);

        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_RECEPTION_START.formatted(encodedFile));
        try {
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_RECEIVING_FILE.formatted(inputFile));
            long fileSize = dataIn.readLong();
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_SIZE.formatted(fileSize));

            if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_FILE_TOO_LARGE + ": " + fileSize);
                throw new IOException(Constants.ErrorMessages.ERR_FILE_TOO_LARGE + ": " + fileSize);
            }

            int checksumLength = dataIn.readInt();
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_LENGTH.formatted(checksumLength));

            if (checksumLength <= 0 || checksumLength > 64) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_INVALID_CHECKSUM.formatted(checksumLength));
                throw new IOException(Constants.ErrorMessages.ERR_INVALID_CHECKSUM.formatted(checksumLength));
            }

            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_RECEIVED);

            byte[] fileContent = processFile(dataIn, fileSize);
            if (!verifyChecksum(fileContent, expectedChecksum)) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_CHECKSUM_VERIFICATION);
                throw new IOException(Constants.ErrorMessages.ERR_CHECKSUM_VERIFICATION);
            }
            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_CHECKSUM_VERIFIED);
            saveFileToDatabase(sanitizedFile, fileContent, fileSize, expectedChecksum);

            logger.log(Logger.Level.INFO, "FileManager",
                    Constants.SuccessMessages.SUCCESS_FILE_RECEIVED + ": " + sanitizedFile);
        } catch (IOException | NoSuchAlgorithmException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_FILE_TRANSFER + ": " + inputFile, e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_FILE_TRANSFER, e);
        }
    }

    private byte[] processFile(DataInputStream dataIn, long fileSize) throws IOException {
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

            if (fileSize > 1000000 && (fileSize - remaining) - lastLoggedProgress > fileSize / 5) {
                lastLoggedProgress = fileSize - remaining;
                int progress = (int)((fileSize - remaining) * 100 / fileSize);
                logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_RECEIVE_PROGRESS.formatted(progress));
            }
        }

        logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_TRANSFER_COMPLETE);
        return fileBuffer.toByteArray();
    }

    private void saveFileToDatabase(String inputFile, byte[] content, long fileSize, byte[] checksum) throws SQLException {
        Database.executeWithConnection(conn -> {
            try {
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM files WHERE filename = ?")) {
                    pstmt.setString(1, inputFile);
                    pstmt.executeUpdate();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO files (filename, content, file_size, checksum) VALUES (?, ?, ?, ?)")) {
                    pstmt.setString(1, inputFile);
                    pstmt.setBytes(2, content);
                    pstmt.setLong(3, fileSize);
                    pstmt.setBytes(4, checksum);
                    pstmt.executeUpdate();
                    logger.log(Logger.Level.INFO, "FileManager", Constants.SuccessMessages.SUCCESS_FILE_SAVED + ": " + inputFile);
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, "FileManager");
    }

    public void sendFile(String encodedFile, OutputStream outputStream) {
        String inputFile = decodeFile(encodedFile);
        try {
            String sanitizedFile;
            try {
                sanitizedFile = processFile(inputFile);
            } catch (RuntimeException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.InfoMessages.INFO_BLOCKED_DOWNLOAD_REJECTED.formatted(inputFile));
                DataOutputStream dataOutput = new DataOutputStream(outputStream);
                dataOutput.writeLong(-1);
                dataOutput.flush();
                return;
            }

            DataOutputStream dataOutput = new DataOutputStream(new BufferedOutputStream(outputStream, BUFFER_SIZE));
            FileData fileData = retreiveDatabase(sanitizedFile);

            if (fileData == null) {
                logger.log(Logger.Level.WARNING, "FileManager", Constants.ErrorMessages.ERR_FILE_NOT_FOUND_DB + ": " + sanitizedFile);
                dataOutput.writeLong(-1); 
                dataOutput.flush();
                return;
            }

            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_SENDING_FILE.formatted(sanitizedFile));
            dataOutput.writeLong(fileData.fileSize);

            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_SENDING_FILE_SIZE.formatted(fileData.fileSize));
            dataOutput.writeInt(fileData.checksum.length);
            dataOutput.write(fileData.checksum);

            logger.log(Logger.Level.INFO, "FileManager",
                    Constants.InfoMessages.INFO_SENDING_CHECKSUM.formatted(fileData.checksum.length));
            dataOutput.flush();

            dataOutput.write(fileData.content);
            dataOutput.flush();

            logger.log(Logger.Level.INFO, "FileManager", Constants.InfoMessages.INFO_FILE_SENT.formatted(inputFile));
        } catch (IOException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_RETRIEVE_FAILED.formatted(inputFile), e);
            throw new RuntimeException(Constants.ErrorMessages.ERR_RETRIEVE_FAILED.formatted(inputFile), e);
        }
    }

    public boolean deleteFile(String encodedFile) {
        String inputFile = decodeFile(encodedFile);
        String sanitizedFile = FileValidation.sanitizeFile(inputFile);

        return Database.queryConnection(conn -> {
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM files WHERE filename = ?")) {
                    pstmt.setString(1, sanitizedFile);
                    int processedRows = pstmt.executeUpdate();

                    boolean deleted = processedRows > 0;
                    if (deleted) {
                        logger.log(Logger.Level.INFO, "FileManager", Constants.SuccessMessages.SUCCESS_FILE_DELETED + ": " + sanitizedFile);
                    } else {
                        logger.log(Logger.Level.WARNING, "FileManager", Constants.ErrorMessages.ERR_FILE_NOT_FOUND_DB + ": " + sanitizedFile);
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

    private FileData retreiveDatabase(String inputFile) throws SQLException {
        return Database.queryConnection(conn -> {
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT content, file_size, checksum FROM files WHERE filename = ?")) {
                    pstmt.setString(1, inputFile);

                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            byte[] content = rs.getBytes("content");
                            long fileSize = rs.getLong("file_size");
                            byte[] checksum = rs.getBytes("checksum");

                            return new FileData(content, fileSize, checksum);
                        }
                        return null; 
                    }
                }
            } catch (SQLException e) {
                logger.log(Logger.Level.ERROR, "FileManager", Constants.ErrorMessages.ERR_SQL.formatted(e.getMessage()), e);
                throw new RuntimeException(e);
            }
        }, null, "FileManager");
    }

    public String listFiles() {
        return Database.queryConnection(conn -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(Constants.InfoMessages.INFO_FILE_LIST_HEADER).append('\n');

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT filename, file_size FROM files ORDER BY filename")) {

                    try (ResultSet rs = pstmt.executeQuery()) {
                        boolean hasFiles = false;

                        while (rs.next()) {
                            hasFiles = true;
                            String inputFile = rs.getString("filename");
                            long fileSize = rs.getLong("file_size");

                            sb.append(Constants.InfoMessages.INFO_FILE_LIST_ENTRY.formatted(inputFile, fileSize)).append('\n');
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