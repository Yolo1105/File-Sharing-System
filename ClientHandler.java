import java.io.*;
import java.net.Socket;
import java.util.StringTokenizer;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private static final FileManager fileManager = new FileManager();

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))
        ) {
            writer.write("Welcome to the File Server. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST\n");
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) continue;
                String command = tokenizer.nextToken().toUpperCase();
                switch (command) {
                    case "UPLOAD":
                        fileManager.receiveFile(tokenizer.nextToken(), socket.getInputStream());
                        DBLogger.log("UPLOAD", tokenizer.nextToken());
                        writer.write("Upload successful.\n");
                        break;
                    case "DOWNLOAD":
                        fileManager.sendFile(tokenizer.nextToken(), socket.getOutputStream());
                        DBLogger.log("DOWNLOAD", tokenizer.nextToken());
                        writer.write("Download complete.\n");
                        break;
                    case "LIST":
                        writer.write(fileManager.listFiles());
                        break;
                    default:
                        writer.write("Unknown command.\n");
                }
                writer.flush();
            }
        } catch (IOException e) {
            System.out.println("Connection closed: " + socket);
        }
    }
}
