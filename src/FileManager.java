import java.io.*;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class FileManager {
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = 32768; // Increased from 8192 to 32KB for better performance

    // Keep this for backward compatibility with some code referencing it
    public static final String SHARED_DIR = Config.getFilesDirectory();

    public FileManager() {
        logger.log(Logger.Level.INFO, "FileManager", "FileManager initialized");
    }

    /**
     * Verifies database tables needed for file storage
     * This is called AFTER database is initialized by MainServer
     */
    public void verifyDatabaseTables() {
        ConnectionPool pool = ConnectionPool.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();

            // Check if files table exists
            boolean tableExists = false;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "files", null)) {
                tableExists = rs.next();
            }

            if (!tableExists) {
                try (Statement stmt = conn.createStatement()) {
                    // Create files table if it doesn't exist
                    stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS files (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            filename TEXT NOT NULL UNIQUE,
                            content BLOB NOT NULL,
                            file_size INTEGER NOT NULL,
                            checksum BLOB NOT NULL,
                            upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
                }
                logger.log(Logger.Level.INFO, "FileManager", "Created database files table");
            } else {
                logger.log(Logger.Level.INFO, "FileManager", "Database files table already exists");
            }
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to verify database tables: " + e.getMessage(), e);
        } finally {
            pool.releaseConnection(conn);
        }
    }

    public void receiveFile(String encodedFileName, DataInputStream dataIn) {
        logger.log(Logger.Level.INFO, "FileManager", "Starting file reception for: " + encodedFileName);

        String fileName;
        try {
            // Decode the URL-encoded filename
            fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8.toString());

            // Check for .txt extension
            if (!fileName.toLowerCase().endsWith(".txt")) {
                logger.log(Logger.Level.ERROR, "FileManager", "Rejected non-txt file: " + fileName);
                throw new RuntimeException("Only .txt files are allowed");
            }
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to decode filename: " + encodedFileName, e);
            throw new RuntimeException("Failed to decode filename", e);
        }

        try {
            logger.log(Logger.Level.INFO, "FileManager", "Receiving file: " + fileName);

            // Read file size directly from DataInputStream
            long fileSize = dataIn.readLong();
            logger.log(Logger.Level.INFO, "FileManager", "File size reported: " + fileSize + " bytes");

            if (fileSize <= 0 || fileSize > 10_000_000_000L) { // 10GB max
                logger.log(Logger.Level.ERROR, "FileManager", "Invalid file size: " + fileSize);
                throw new IOException("Invalid file size: " + fileSize);
            }

            // Read checksum
            int checksumLength = dataIn.readInt();
            logger.log(Logger.Level.INFO, "FileManager", "Checksum length: " + checksumLength);

            if (checksumLength <= 0 || checksumLength > 64) {
                logger.log(Logger.Level.ERROR, "FileManager", "Invalid checksum length: " + checksumLength);
                throw new IOException("Invalid checksum length: " + checksumLength);
            }

            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);
            logger.log(Logger.Level.INFO, "FileManager", "Checksum received");

            // Sanitize filename
            String sanitizedFileName = sanitizeFileName(fileName);

            // Read file data into buffer
            ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = fileSize;
            int count;
            long lastLoggedProgress = 0;

            logger.log(Logger.Level.INFO, "FileManager", "Starting file data transfer for: " + fileName);

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

            // Get the file content as byte array
            byte[] fileContent = fileBuffer.toByteArray();

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(fileContent);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                logger.log(Logger.Level.ERROR, "FileManager", "Checksum verification failed for file: " + fileName);
                throw new IOException("File transfer failed: Checksum verification failed");
            }
            logger.log(Logger.Level.INFO, "FileManager", "Checksum verification successful");

            // Save file to database
            saveFileToDatabase(sanitizedFileName, fileContent, fileSize, expectedChecksum);

            logger.log(Logger.Level.INFO, "FileManager", "File received and saved to database: " + sanitizedFileName);

        } catch (IOException | NoSuchAlgorithmException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to receive file: " + fileName, e);
            throw new RuntimeException("File transfer failed", e);
        }
    }

    private void saveFileToDatabase(String fileName, byte[] content, long fileSize, byte[] checksum) throws SQLException {
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

                logger.log(Logger.Level.INFO, "FileManager", "File saved to database: " + fileName);
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }

    public void sendFile(String encodedFileName, OutputStream outputStream) {
        String fileName;
        try {
            fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to decode filename: " + encodedFileName, e);
            throw new RuntimeException("Failed to decode filename", e);
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

            // Sanitize the filename to prevent SQL injection
            String sanitizedFileName = sanitizeFileName(fileName);

            // Use buffered streams for better performance
            DataOutputStream dataOut = new DataOutputStream(new BufferedOutputStream(outputStream, BUFFER_SIZE));

            // Retrieve file from database
            FileData fileData = getFileFromDatabase(sanitizedFileName);

            if (fileData == null) {
                logger.log(Logger.Level.WARNING, "FileManager", "File not found in database: " + sanitizedFileName);
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
            logger.log(Logger.Level.INFO, "FileManager", "Sending checksum of length: " + fileData.checksum.length + " bytes");
            dataOut.flush();

            // Send file contents
            byte[] content = fileData.content;
            dataOut.write(content);
            dataOut.flush();

            logger.log(Logger.Level.INFO, "FileManager", "File transfer complete: " + fileName);

        } catch (IOException | SQLException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to send file: " + fileName, e);
            throw new RuntimeException("Failed to send file: " + fileName, e);
        }
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
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to list files", e);
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
        // Replace any path-like characters
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private byte[] calculateChecksum(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    private byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
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