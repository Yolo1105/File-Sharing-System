import java.io.*;
import java.util.concurrent.locks.ReentrantLock;

public class FileManager {
    private static final String SHARED_DIR = "file_data/";
    private final ReentrantLock lock = new ReentrantLock();

    public FileManager() {
        File dir = new File(SHARED_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public void receiveFile(String fileName, InputStream inputStream) {
        lock.lock();
        try (FileOutputStream fos = new FileOutputStream(SHARED_DIR + fileName)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) > 0) {
                fos.write(buffer, 0, count);
                if (count < buffer.length) break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public void sendFile(String fileName, OutputStream outputStream) {
        lock.lock();
        try (FileInputStream fis = new FileInputStream(SHARED_DIR + fileName)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = fis.read(buffer)) > 0) {
                outputStream.write(buffer, 0, count);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public String listFiles() {
        File folder = new File(SHARED_DIR);
        StringBuilder sb = new StringBuilder();
        for (File file : folder.listFiles()) {
            if (file.isFile()) sb.append(file.getName()).append("\n");
        }
        return sb.toString();
    }
}
