import java.io.*;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileManager {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = Config.getBufferSize();
    private static final AtomicBoolean tablesVerified = new AtomicBoolean(false);

    // Error message constants
    private static final String ERR_DECODE_FILENAME = "Failed to decode filename";
    private static final String ERR_FILE_TRANSFER = "File transfer failed";
    private static final String ERR_SAVE_FILE = "Failed to save file to database";
    private static final String ERR_RETRIEVE_FILE = "Failed to retrieve file from database";
    private static final String ERR_FILE_NOT_FOUND = "File not found in database";
    private static final String ERR_LIST_FILES = "Failed to list files";
    private static final String ERR_INVALID_FILE_SIZE = "Invalid file size";
    private static final String ERR_INVALID_CHECKSUM = "Invalid checksum length";
    private static final String ERR_CHECKSUM_VERIFICATION = "Checksum verification failed";
    private static final String ERR_NON_TXT_FILE = "Only .txt files are allowed";

    // Success messages
    private static final String SUCCESS_FILE_SAVED = "File saved to database";
    private static final String SUCCESS_FILE_SENT = "File sent successfully";
    private static final String SUCCESS_FILE_RECEIVED = "File received successfully";

    /**
     * Constructor - initializes the FileManager instance
     */
    public FileManager() {
        logger.log(Logger.Level.INFO, "FileManager", "FileManager initialized");
    }

    /**
     * Verifies the database tables needed for file storage
     * This is now a lightweight method that just marks tables as verified
     * since the actual creation is handled by ConnectionPool
     */
    public void verifyDatabaseTables() {
        if (tablesVerified.get()) {
            logger.log(Logger.Level.DEBUG, "FileManager", "Database tables already verified");
            return;
        }

        logger.log(Logger.Level.INFO, "FileManager", "Verifying database tables");

        // Actual table creation is now handled by ConnectionPool.initializeDatabaseSchema()
        // Just mark them as verified here
        tablesVerified.set(true);
        logger.log(Logger.Level.INFO, "FileManager", "Database tables verified");
    }

    /**
     * Receives a file from a client and stores it in the database
     * @param encodedFileName URL-encoded filename
     * @param dataIn Input stream for file data
     */
    public void receiveFile(String encodedFileName, DataInputStream dataIn) {
        logger.log(Logger.Level.INFO, "FileManager", "Starting file reception for: " + encodedFileName);

        String fileName;
        try {
            // Decode the URL-encoded filename
            fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8.toString());

            // Check for .txt extension
            if (!fileName.toLowerCase().endsWith(".txt")) {
                logger.log(Logger.Level.ERROR, "FileManager", "Rejected non-txt file: " + fileName);
                throw new RuntimeException(ERR_NON_TXT_FILE);
            }
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", ERR_DECODE_FILENAME, e);
            throw new RuntimeException(ERR_DECODE_FILENAME, e);
        }

        try {
            logger.log(Logger.Level.INFO, "FileManager", "Receiving file: " + fileName);

            // Read file size and validate
            long fileSize = dataIn.readLong();
            logger.log(Logger.Level.INFO, "FileManager", "File size reported: " + fileSize + " bytes");

            if (fileSize <= 0 || fileSize > 10_000_000_000L) { // 10GB max
                logger.log(Logger.Level.ERROR, "FileManager", ERR_INVALID_FILE_SIZE + ": " + fileSize);
                throw new IOException(ERR_INVALID_FILE_SIZE + ": " + fileSize);
            }

            // Read and validate checksum
            int checksumLength = dataIn.readInt();
            logger.log(Logger.Level.INFO, "FileManager", "Checksum length: " + checksumLength);

            if (checksumLength <= 0 || checksumLength > 64) {
                logger.log(Logger.Level.ERROR, "FileManager", ERR_INVALID_CHECKSUM + ": " + checksumLength);
                throw new IOException(ERR_INVALID_CHECKSUM + ": " + checksumLength);
            }

            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);
            logger.log(Logger.Level.INFO, "FileManager", "Checksum received");

            // Sanitize filename
            String sanitizedFileName = sanitizeFileName(fileName);

            // Read file data into buffer
            byte[] fileContent = readFileContent(dataIn, fileSize);

            // Verify checksum
            if (!doChecksumVerification(fileContent, expectedChecksum)) {
                logger.log(Logger.Level.ERROR, "FileManager", ERR_CHECKSUM_VERIFICATION);
                throw new IOException(ERR_CHECKSUM_VERIFICATION);
            }
            logger.log(Logger.Level.INFO, "FileManager", "Checksum verification successful");

            // Save file to database
            saveFileToDatabase(sanitizedFileName, fileContent, fileSize, expectedChecksum);

            logger.log(Logger.Level.INFO, "FileManager",
                    SUCCESS_FILE_RECEIVED + ": " + sanitizedFileName);

        } catch (IOException | NoSuchAlgorithmException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", ERR_FILE_TRANSFER + ": " + fileName, e);
            throw new RuntimeException(ERR_FILE_TRANSFER, e);
        }
    }

    /**
     * Reads file content from an input stream
     * @param dataIn Data input stream
     * @param fileSize Expected file size
     * @return Byte array containing the file content
     */
    private byte[] readFileContent(DataInputStream dataIn, long fileSize) throws IOException {
        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = fileSize;
        int count;
        long lastLoggedProgress = 0;

        logger.log(Logger.Level.INFO, "FileManager", "Starting file data transfer");

        while (remaining > 0 &&
                (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
            fileBuffer.write(buffer, 0, count);
            remaining -= count;

            // Log progress less frequently for better performance
            if (fileSize > 1000000 && (fileSize - remaining) - lastLoggedProgress > fileSize / 5) {
                lastLoggedProgress = fileSize - remaining;
                int progress = (int)((fileSize - remaining) * 100 / fileSize);
                logger.log(Logger.Level.INFO, "FileManager", "Receive progress: " + progress + "%");
            }
        }

        logger.log(Logger.Level.INFO, "FileManager", "File data transfer complete");

        return fileBuffer.toByteArray();
    }

    /**
     * Helper method to check checksums
     * @param fileContent The file content
     * @param expectedChecksum The expected checksum
     * @return true if checksums match, false otherwise
     */
    private boolean doChecksumVerification(byte[] fileContent, byte[] expectedChecksum)
            throws NoSuchAlgorithmException {
        byte[] actualChecksum = calculateChecksum(fileContent);
        return MessageDigest.isEqual(expectedChecksum, actualChecksum);
    }

    /**
     * Saves a file to the database
     */
    private void saveFileToDatabase(String fileName, byte[] content, long fileSize, byte[] checksum)
            throws SQLException {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();

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

                logger.log(Logger.Level.INFO, "FileManager", SUCCESS_FILE_SAVED + ": " + fileName);
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Sends a file to a client
     * @param encodedFileName URL-encoded filename
     * @param outputStream Output stream to send file data
     */
    public void sendFile(String encodedFileName, OutputStream outputStream) {
        String fileName;
        try {
            fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", ERR_DECODE_FILENAME, e);
            throw new RuntimeException(ERR_DECODE_FILENAME, e);
        }

        try {
            // Reject non-txt file downloads
            if (!fileName.toLowerCase().endsWith(".txt")) {
                logger.log(Logger.Level.ERROR, "FileManager", "Rejected non-txt file download: " + fileName);
                DataOutputStream dataOut = new DataOutputStream(outputStream);
                dataOut.writeLong(-1);  // Signal rejection
                dataOut.flush();
                return;
            }

            // Sanitize the filename
            String sanitizedFileName = sanitizeFileName(fileName);

            // Use buffered streams for better performance
            DataOutputStream dataOut = new DataOutputStream(
                    new BufferedOutputStream(outputStream, BUFFER_SIZE));

            // Retrieve file from database
            FileData fileData = getFileFromDatabase(sanitizedFileName);

            if (fileData == null) {
                logger.log(Logger.Level.WARNING, "FileManager", ERR_FILE_NOT_FOUND + ": " + sanitizedFileName);
                dataOut.writeLong(-1); // Indicate file not found
                dataOut.flush();
                return;
            }

            logger.log(Logger.Level.INFO, "FileManager", "Sending file: " + sanitizedFileName);

            // Send file size
            dataOut.writeLong(fileData.fileSize);
            logger.log(Logger.Level.INFO, "FileManager", "Sending file size: " + fileData.fileSize + " bytes");

            // Send checksum
            dataOut.writeInt(fileData.checksum.length);
            dataOut.write(fileData.checksum);
            logger.log(Logger.Level.INFO, "FileManager",
                    "Sending checksum of length: " + fileData.checksum.length + " bytes");
            dataOut.flush();

            // Send file contents
            dataOut.write(fileData.content);
            dataOut.flush();

            logger.log(Logger.Level.INFO, "FileManager", SUCCESS_FILE_SENT + ": " + fileName);

        } catch (IOException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", ERR_RETRIEVE_FILE + ": " + fileName, e);
            throw new RuntimeException(ERR_RETRIEVE_FILE + ": " + fileName, e);
        }
    }

    /**
     * Internal class to hold file data
     */
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

    /**
     * Retrieves a file from the database
     * @param fileName Filename to retrieve
     * @return FileData object containing file content and metadata, or null if not found
     */
    private FileData getFileFromDatabase(String fileName) throws SQLException {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();

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
                }
            }

            return null; // File not found
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Lists all files in the database
     * @return String representation of the file list
     */
    public String listFiles() {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            StringBuilder sb = new StringBuilder();
            sb.append("Available files:\n");

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT filename, file_size FROM files ORDER BY filename")) {

                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean hasFiles = false;

                    while (rs.next()) {
                        hasFiles = true;
                        String fileName = rs.getString("filename");
                        long fileSize = rs.getLong("file_size");

                        sb.append(" - ").append(fileName)
                                .append(" (").append(fileSize).append(" bytes)")
                                .append("\n");
                    }

                    if (!hasFiles) {
                        sb.append("No files available.\n");
                    }
                }
            }

            return sb.toString();
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", ERR_LIST_FILES, e);
            return "Error listing files: " + e.getMessage();
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Sanitizes a filename to prevent SQL injection and other issues
     * @param filename The filename to sanitize
     * @return A sanitized filename
     */
    private String sanitizeFileName(String filename) {
        // Replace any path-like characters with underscores
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Calculates SHA-256 checksum of data
     */
    private byte[] calculateChecksum(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    /**
     * Calculates SHA-256 checksum of a file
     */
    public byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
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

    /**
     * Verifies file content against expected checksum
     * Utility method that can be used by other classes
     * @param data The file content
     * @param expectedChecksum The expected checksum
     * @return True if checksum matches, false otherwise
     */
    public boolean verifyChecksum(byte[] data, byte[] expectedChecksum) throws NoSuchAlgorithmException {
        byte[] actualChecksum = calculateChecksum(data);
        return MessageDigest.isEqual(expectedChecksum, actualChecksum);
    }
}