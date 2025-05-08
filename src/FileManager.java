import java.io.*;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.charset.StandardCharsets;

public class FileManager {
    public static final String SHARED_DIR = Config.getFilesDirectory();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final Logger logger = Logger.getInstance();
    private static final int BUFFER_SIZE = 32768; // Increased from 8192 to 32KB for better performance

    public FileManager() {
        File dir = new File(SHARED_DIR);
        if (!dir.exists()) {
            logger.log(Logger.Level.INFO, "FileManager", "Creating server storage directory: " + SHARED_DIR);
            boolean created = dir.mkdirs();
            if (!created) {
                logger.log(Logger.Level.ERROR, "FileManager", "Failed to create server storage directory: " + SHARED_DIR);
            }
        }
    }

    public void receiveFile(String encodedFileName, DataInputStream dataIn) {
        // Use write lock for file upload
        lock.writeLock().lock();
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

            // Sanitize filename and prepare temp/final paths
            String sanitizedFileName = sanitizeFileName(fileName);
            String tempFileName = "temp_" + System.currentTimeMillis() + "_" + sanitizedFileName.trim();
            File tempFile = new File(SHARED_DIR + tempFileName);
            File outputFile = new File(SHARED_DIR + sanitizedFileName.trim());

            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE]; // Increased buffer size
                long remaining = fileSize;
                int count;
                long lastLoggedProgress = 0;

                logger.log(Logger.Level.INFO, "FileManager", "Starting file data transfer for: " + fileName);

                while (remaining > 0 &&
                        (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, count);
                    remaining -= count;

                    // Log progress less frequently for better performance
                    if (fileSize > 1000000 && (fileSize - remaining) - lastLoggedProgress > fileSize / 5) {
                        lastLoggedProgress = fileSize - remaining;
                        int progress = (int)((fileSize - remaining) * 100 / fileSize);
                        logger.log(Logger.Level.INFO, "FileManager", "Receive progress: " + progress + "%");
                    }
                }
                fos.flush();
                logger.log(Logger.Level.INFO, "FileManager", "File data transfer complete");
            }

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(tempFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                logger.log(Logger.Level.ERROR, "FileManager", "Checksum verification failed for file: " + fileName);
                tempFile.delete();
                throw new IOException("File transfer failed: Checksum verification failed");
            }
            logger.log(Logger.Level.INFO, "FileManager", "Checksum verification successful");

            // Replace old file if exists
            if (outputFile.exists()) {
                outputFile.delete();
            }
            if (!tempFile.renameTo(outputFile)) {
                logger.log(Logger.Level.ERROR, "FileManager", "Failed to rename temp file to: " + fileName);
                throw new IOException("File transfer failed: Could not save file");
            }

            logger.log(Logger.Level.INFO, "FileManager", "File received and saved to: " + outputFile.getAbsolutePath());

        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to receive file: " + fileName, e);
            throw new RuntimeException("File transfer failed", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void sendFile(String encodedFileName, OutputStream outputStream) {
        // Use read lock for file download (allows multiple simultaneous downloads)
        lock.readLock().lock();

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

            // Sanitize the filename to prevent directory traversal attacks
            String sanitizedFileName = sanitizeFileName(fileName);
            File inputFile = new File(SHARED_DIR + sanitizedFileName.trim());

            // Use buffered streams for better performance
            DataOutputStream dataOut = new DataOutputStream(new BufferedOutputStream(outputStream, BUFFER_SIZE));

            if (!inputFile.exists() || !inputFile.isFile()) {
                logger.log(Logger.Level.WARNING, "FileManager", "File not found: " + inputFile.getAbsolutePath());
                dataOut.writeLong(-1); // Indicate file not found
                dataOut.flush();
                return;
            }

            logger.log(Logger.Level.INFO, "FileManager", "Sending file: " + inputFile.getName());

            long fileSize = inputFile.length();
            byte[] checksum = calculateChecksum(inputFile);

            // Send file size
            dataOut.writeLong(fileSize);
            logger.log(Logger.Level.INFO, "FileManager", "Sending file size: " + fileSize + " bytes");

            // Send checksum
            dataOut.writeInt(checksum.length);
            dataOut.write(checksum);
            logger.log(Logger.Level.INFO, "FileManager", "Sending checksum of length: " + checksum.length + " bytes");
            dataOut.flush();

            // Send file contents with buffered streams
            try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(inputFile), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE]; // Increased buffer size
                int count;
                long totalSent = 0;
                long lastLoggedProgress = 0;

                while ((count = fis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, count);
                    totalSent += count;

                    // Log progress less frequently for better performance
                    if (fileSize > 1000000 && totalSent - lastLoggedProgress > fileSize / 5) {
                        lastLoggedProgress = totalSent;
                        int progress = (int)(totalSent * 100 / fileSize);
                        logger.log(Logger.Level.INFO, "FileManager", "Send progress: " + progress + "%");
                    }
                }
                dataOut.flush();
                logger.log(Logger.Level.INFO, "FileManager", "File transfer complete: " + fileName);
            }

        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to send file: " + fileName, e);
            throw new RuntimeException("Failed to send file: " + fileName, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String listFiles() {
        // Use read lock for listing files
        lock.readLock().lock();
        try {
            File folder = new File(SHARED_DIR);
            StringBuilder sb = new StringBuilder();
            sb.append("Available files:\n");
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) {
                boolean hasRegularFiles = false;
                for (File file : files) {
                    if (file.isFile() && !file.getName().startsWith("temp_")) {
                        hasRegularFiles = true;
                        String displayName = file.getName();
                        sb.append(" - ").append(displayName)
                                .append(" (").append(file.length()).append(" bytes)")
                                .append("\n");
                    }
                }
                if (!hasRegularFiles) {
                    sb.append("No files available.\n");
                }
            } else {
                sb.append("No files available.\n");
            }
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Sanitizes a filename to prevent directory traversal attacks
     * @param filename The filename to sanitize
     * @return A sanitized filename
     */
    private String sanitizeFileName(String filename) {
        // Replace any path-like characters
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
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