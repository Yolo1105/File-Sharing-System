import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.ReentrantLock;

public class FileManager {
    public static final String SHARED_DIR = Config.getFilesDirectory();
    private final ReentrantLock lock = new ReentrantLock();
    private static final Logger logger = Logger.getInstance();

    public FileManager() {
        File dir = new File(SHARED_DIR);
        if (!dir.exists()) {
            logger.log(Logger.Level.INFO, "FileManager", "Creating server storage directory: " + SHARED_DIR);
            dir.mkdirs();
        }
    }

    public void receiveFile(String fileName, InputStream inputStream) {
        lock.lock();
        try {
            logger.log(Logger.Level.INFO, "FileManager", "Receiving file: " + fileName);
            DataInputStream dataIn = new DataInputStream(inputStream);
            long fileSize = dataIn.readLong();
            logger.log(Logger.Level.INFO, "FileManager", "Expecting file size: " + fileSize + " bytes");

            // Read checksum
            int checksumLength = dataIn.readInt();
            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);

            File outputFile = new File(SHARED_DIR + fileName.trim());
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[4096];
                long remaining = fileSize;
                int count;
                while (remaining > 0 &&
                        (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, count);
                    remaining -= count;
                }
                fos.flush();
            }

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(outputFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                logger.log(Logger.Level.ERROR, "FileManager", "Checksum verification failed for file: " + fileName);
                outputFile.delete(); // Delete corrupted file
                throw new IOException("File transfer failed: Checksum verification failed");
            }

            logger.log(Logger.Level.INFO, "FileManager", "File received and saved to: " + outputFile.getAbsolutePath());
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to receive file: " + fileName, e);
        } finally {
            lock.unlock();
        }
    }

    public void sendFile(String fileName, OutputStream outputStream) {
        lock.lock();
        try {
            File inputFile = new File(SHARED_DIR + fileName.trim());
            if (!inputFile.exists()) {
                logger.log(Logger.Level.WARNING, "FileManager", "File not found: " + inputFile.getAbsolutePath());
                DataOutputStream dataOut = new DataOutputStream(outputStream);
                dataOut.writeLong(-1); // Indicate file not found
                return;
            }

            logger.log(Logger.Level.INFO, "FileManager", "Sending file: " + inputFile.getName());
            DataOutputStream dataOut = new DataOutputStream(outputStream);
            long fileSize = inputFile.length();
            dataOut.writeLong(fileSize);
            logger.log(Logger.Level.INFO, "FileManager", "Sending file size: " + fileSize + " bytes");

            // Calculate and send checksum
            byte[] checksum = calculateChecksum(inputFile);
            dataOut.writeInt(checksum.length);
            dataOut.write(checksum);
            logger.log(Logger.Level.INFO, "FileManager", "Sending checksum of length: " + checksum.length + " bytes");

            try (FileInputStream fis = new FileInputStream(inputFile)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, count);
                }
                dataOut.flush();
                logger.log(Logger.Level.INFO, "FileManager", "File transfer complete: " + fileName);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.log(Logger.Level.ERROR, "FileManager", "Failed to send file: " + fileName, e);
        } finally {
            lock.unlock();
        }
    }

    public String listFiles() {
        File folder = new File(SHARED_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("Available files:\n");
        File[] files = folder.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isFile()) {
                    sb.append(" - ").append(file.getName())
                            .append(" (").append(file.length()).append(" bytes)")
                            .append("\n");
                }
            }
        } else {
            sb.append("No files available.\n");
        }
        return sb.toString();
    }

    private byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
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