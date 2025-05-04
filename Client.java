import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 12345);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             Scanner scanner = new Scanner(System.in)) {

            System.out.println(reader.readLine()); 

            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine();
                writer.write(command + "\n");
                writer.flush();

                String response;
                while ((response = reader.readLine()) != null && !response.isEmpty()) {
                    System.out.println(response);
                    if (!reader.ready()) break;
                }

                if (command.equalsIgnoreCase("exit")) break;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
