import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

public class Client {
    private static final Logger logger = Logger.getInstance();

    public static void main(String[] args) {
        logger.log(Logger.Level.INFO, "Client", "Starting client...");

        String serverHost = Config.getProperty("server.host", "localhost");
        int serverPort = Config.getServerPort();

        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             Scanner scanner = new Scanner(System.in)) {

            logger.log(Logger.Level.INFO, "Client", "Connected to server at " + serverHost + ":" + serverPort);
            String welcomeMessage = reader.readLine();
            System.out.println(welcomeMessage);  // Display welcome message

            // Ask for client name
            System.out.print("Enter your client name: ");
            String clientName = scanner.nextLine().trim();
            writer.write("CLIENT_ID " + clientName + "\n");
            writer.flush();
            System.out.println(reader.readLine()); // Display available commands

            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine().trim();
                String[] parts = command.split("\\s+", 2);

                if (parts.length == 0 || parts[0].isEmpty()) {
                    continue;
                }

                if (parts[0].equalsIgnoreCase("UPLOAD") && parts.length == 2) {
                    String filename = parts[1];
                    File file = new File("client_files/" + filename);
                    if (!file.exists()) {
                        System.out.println("[ERROR] File not found: " + file.getAbsolutePath());
                        continue;
                    }

                    long fileSize = file.length();
                    System.out.println("[INFO] Preparing to upload: " + filename + " (" + fileSize + " bytes)");
                    writer.write(command + "\n");
                    writer.flush();
                    System.out.println("[DEBUG] Sent command to server: " + command);

                    // Send file size
                    dataOut.writeLong(fileSize);
                    dataOut.flush();

                    // Calculate and send checksum
                    byte[] checksum = calculateChecksum(file);
                    dataOut.writeInt(checksum.length);
                    dataOut.write(checksum);
                    dataOut.flush();
                    System.out.println("[DEBUG] Sent file size and checksum");

                    try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = fileIn.read(buffer)) > 0) {
                            dataOut.write(buffer, 0, count);
                        }
                        dataOut.flush();
                        System.out.println("[INFO] File upload completed.");
                    }

                    String response = reader.readLine();
                    if (response != null) {
                        System.out.println("[SERVER] " + response);
                    }

                } else if (parts[0].equalsIgnoreCase("DOWNLOAD") && parts.length == 2) {
                    String filename = parts[1];
                    writer.write(command + "\n");
                    writer.flush();

                    long fileSize = dataIn.readLong();
                    if (fileSize < 0) {
                        System.out.println("[ERROR] Server reported file not found: " + filename);
                        continue;
                    }

                    System.out.println("[INFO] Receiving file: " + filename + " (" + fileSize + " bytes)");

                    // Receive checksum
                    int checksumLength = dataIn.readInt();
                    byte[] expectedChecksum = new byte[checksumLength];
                    dataIn.readFully(expectedChecksum);

                    // Create downloads directory if it doesn't exist
                    File dir = new File("downloads");
                    dir.mkdirs();
                    File outFile = new File(dir, filename);

                    try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        byte[] buffer = new byte[4096];
                        long remaining = fileSize;
                        int count;
                        while (remaining > 0 &&
                                (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                            fileOut.write(buffer, 0, count);
                            remaining -= count;
                        }
                        fileOut.flush();
                    }

                    // Verify checksum
                    byte[] actualChecksum = calculateChecksum(outFile);
                    boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

                    if (!checksumMatch) {
                        System.out.println("[ERROR] Downloaded file is corrupted. Checksum verification failed.");
                        outFile.delete(); // Delete corrupted file
                    } else {
                        System.out.println("[INFO] File downloaded and saved to: " + outFile.getAbsolutePath());
                    }

                    String response = reader.readLine();
                    if (response != null) {
                        System.out.println("[SERVER] " + response);
                    }

                } else if (parts[0].equalsIgnoreCase("LIST")) {
                    writer.write(command + "\n");
                    writer.flush();

                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        System.out.println("[SERVER] " + line);
                        if (!reader.ready()) break;
                    }

                } else if (parts[0].equalsIgnoreCase("EXIT")) {
                    writer.write("EXIT\n");
                    writer.flush();
                    System.out.println("Exiting client...");
                    break;

                } else {
                    System.out.println("[ERROR] Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, EXIT");
                }
            }

        } catch (IOException e) {
            logger.log(Logger.Level.FATAL, "Client", "Connection error", e);
            System.out.println("[FATAL] Connection error: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            logger.log(Logger.Level.FATAL, "Client", "Checksum algorithm error", e);
            System.out.println("[FATAL] Checksum error: " + e.getMessage());
        }
    }

    private static byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
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