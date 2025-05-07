import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ClientGUI extends JFrame {
    private JTextArea logArea;
    private JTextField inputField;
    private JButton uploadButton, downloadButton, listButton;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;
    private Socket socket;
    private String clientId;

    public ClientGUI() {
        clientId = JOptionPane.showInputDialog(this, "Enter your client name:");
        if (clientId == null || clientId.trim().isEmpty()) System.exit(1);

        setTitle("Client - " + clientId);
        setSize(600, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        logArea = new JTextArea();
        inputField = new JTextField(15);
        uploadButton = new JButton("Upload");
        downloadButton = new JButton("Download");
        listButton = new JButton("List");

        JPanel panel = new JPanel();
        panel.add(new JLabel("Filename:"));
        panel.add(inputField);
        panel.add(uploadButton);
        panel.add(downloadButton);
        panel.add(listButton);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(panel, BorderLayout.SOUTH);

        uploadButton.addActionListener(this::handleUpload);
        downloadButton.addActionListener(this::handleDownload);
        listButton.addActionListener(this::handleList);

        connect();
    }

    private void connect() {
        try {
            socket = new Socket("localhost", Config.getServerPort());
            dataIn = new DataInputStream(socket.getInputStream());
            dataOut = new DataOutputStream(socket.getOutputStream());

            logArea.append(dataIn.readUTF() + "\n");
            dataOut.writeUTF("CLIENT_ID " + clientId);
            logArea.append(dataIn.readUTF() + "\n");

        } catch (IOException e) {
            logArea.append("[ERROR] Could not connect to server.\n");
            e.printStackTrace();
        }
    }

    private void handleUpload(ActionEvent e) {
        String filename = inputField.getText().trim();
        if (filename.isEmpty()) return;
        File file = new File("client_files/" + filename);
        if (!file.exists()) {
            logArea.append("[ERROR] File not found.\n");
            return;
        }

        try {
            dataOut.writeUTF("UPLOAD " + filename);
            dataOut.writeLong(file.length());

            byte[] checksum = calculateChecksum(file);
            dataOut.writeInt(checksum.length);
            dataOut.write(checksum);

            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = bis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, count);
                }
            }

            logArea.append("[SERVER] " + dataIn.readUTF() + "\n");
        } catch (Exception ex) {
            ex.printStackTrace();
            logArea.append("[ERROR] Upload failed.\n");
        }
    }

    private void handleDownload(ActionEvent e) {
        String filename = inputField.getText().trim();
        if (filename.isEmpty()) return;

        try {
            dataOut.writeUTF("DOWNLOAD " + filename);
            long size = dataIn.readLong();

            if (size == -1) {
                logArea.append("[SERVER] " + dataIn.readUTF() + "\n");
                return;
            }

            int checksumLength = dataIn.readInt();
            byte[] expectedChecksum = new byte[checksumLength];
            dataIn.readFully(expectedChecksum);

            File dir = new File("downloads");
            dir.mkdirs();
            File file = new File(dir, filename);

            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                byte[] buffer = new byte[4096];
                long remaining = size;
                int count;
                while (remaining > 0 &&
                        (count = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    bos.write(buffer, 0, count);
                    remaining -= count;
                }
            }

            byte[] actualChecksum = calculateChecksum(file);
            if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
                logArea.append("[ERROR] Checksum mismatch. File deleted.\n");
                file.delete();
                return;
            }

            logArea.append("[SERVER] " + dataIn.readUTF() + "\n");

        } catch (Exception ex) {
            ex.printStackTrace();
            logArea.append("[ERROR] Download failed.\n");
        }
    }

    private void handleList(ActionEvent e) {
        try {
            dataOut.writeUTF("LIST");
            logArea.append(dataIn.readUTF() + "\n");
        } catch (IOException ex) {
            ex.printStackTrace();
            logArea.append("[ERROR] LIST command failed.\n");
        }
    }

    private byte[] calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }
}
