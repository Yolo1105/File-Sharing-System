import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) {
        System.out.println("Starting client...");
        try (Socket socket = new Socket("localhost", 12345);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Connected to server.");
            System.out.println(reader.readLine());  // Welcome message

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine().trim();
                String[] parts = command.split("\\s+");

                if (parts[0].equalsIgnoreCase("UPLOAD") && parts.length == 2) {
                    String filename = parts[1];
                    File file = new File("file_data/" + filename);
                    if (!file.exists()) {
                        System.out.println("[ERROR] File not found: " + file.getAbsolutePath());
                        continue;
                    }

                    long fileSize = file.length();
                    System.out.println("[INFO] Preparing to upload: " + filename + " (" + fileSize + " bytes)");
                    writer.write(command + "\n");
                    writer.flush();
                    System.out.println("[DEBUG] Sent command to server: " + command);

                    DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
                    dataOut.writeLong(fileSize);
                    dataOut.flush();
                    System.out.println("[DEBUG] Sent file size: " + fileSize);

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

                    DataInputStream dataIn = new DataInputStream(socket.getInputStream());
                    long fileSize = dataIn.readLong();
                    System.out.println("[INFO] Receiving file: " + filename + " (" + fileSize + " bytes)");

                    File outFile = new File("downloads/" + filename);
                    outFile.getParentFile().mkdirs();

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
                        System.out.println("[INFO] File downloaded and saved to: " + outFile.getAbsolutePath());
                    }

                } else if (parts[0].equalsIgnoreCase("LIST")) {
                    writer.write(command + "\n");
                    writer.flush();

                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        System.out.println("[SERVER] " + line);
                        if (!reader.ready()) break;
                    }

                } else if (parts[0].equalsIgnoreCase("exit")) {
                    writer.write("exit\n");
                    writer.flush();
                    System.out.println("Exiting client...");
                    break;

                } else {
                    System.out.println("[ERROR] Invalid command.");
                }
            }

        } catch (IOException e) {
            System.out.println("[FATAL] Connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
