import java.io.*;
import java.util.concurrent.locks.ReentrantLock;

public class FileManager {
    private static final String SHARED_DIR = "server_files/";
    private final ReentrantLock lock = new ReentrantLock();

    public FileManager() {
        File dir = new File(SHARED_DIR);
        if (!dir.exists()) {
            System.out.println("[INFO] Creating server storage directory: " + SHARED_DIR);
            dir.mkdirs();
        }
    }

    public void receiveFile(String fileName, InputStream inputStream) {
        lock.lock();
        try {
            System.out.println("[INFO] Receiving file: " + fileName);
            DataInputStream dataIn = new DataInputStream(inputStream);
            long fileSize = dataIn.readLong();
            System.out.println("[DEBUG] Expecting file size: " + fileSize + " bytes");

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
                System.out.println("[INFO] File received and saved to: " + outputFile.getAbsolutePath());
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to receive file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public void sendFile(String fileName, OutputStream outputStream) {
        lock.lock();
        try {
            File inputFile = new File(SHARED_DIR + fileName.trim());
            if (!inputFile.exists()) {
                System.out.println("[WARNING] File not found: " + inputFile.getAbsolutePath());
                return;
            }

            System.out.println("[INFO] Sending file: " + inputFile.getName());
            DataOutputStream dataOut = new DataOutputStream(outputStream);
            long fileSize = inputFile.length();
            dataOut.writeLong(fileSize);
            System.out.println("[DEBUG] Sending file size: " + fileSize + " bytes");

            try (FileInputStream fis = new FileInputStream(inputFile)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, count);
                }
                dataOut.flush();
                System.out.println("[INFO] File transfer complete.");
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to send file: " + e.getMessage());
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public String listFiles() {
        File folder = new File(SHARED_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("Available files:\n");
        for (File file : folder.listFiles()) {
            if (file.isFile()) sb.append(" - ").append(file.getName()).append("\n");
        }
        return sb.toString();
    }
}
