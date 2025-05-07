import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ClientGUI extends JFrame {
    private JTextArea logArea;
    private JTextField downloadField;
    private JButton uploadButton, downloadButton, listButton, viewLogsButton;
    private Socket socket;
    private BufferedReader textReader;
    private BufferedWriter textWriter;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private String clientId;
    private static final Logger logger = Logger.getInstance();

    public ClientGUI() {
        clientId = JOptionPane.showInputDialog(this, "Enter your client name:");
        if (clientId == null || clientId.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Client name required.");
            System.exit(1);
        }

        setTitle("Shared File System - Client: " + clientId);
        setSize(650, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        JPanel inputPanel = new JPanel();
        downloadField = new JTextField(20);
        uploadButton = new JButton("Upload");
        downloadButton = new JButton("Download");
        listButton = new JButton("List Files");
        viewLogsButton = new JButton("View Logs");

        inputPanel.add(new JLabel("Filename:"));
        inputPanel.add(downloadField);
        inputPanel.add(uploadButton);
        inputPanel.add(downloadButton);
        inputPanel.add(listButton);
        inputPanel.add(viewLogsButton);

        add(scrollPane, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);

        uploadButton.addActionListener(this::handleUpload);
        downloadButton.addActionListener(this::handleDownload);
        listButton.addActionListener(this::handleListFiles);
        viewLogsButton.addActionListener(this::handleViewLogs);

        connectToServer();
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", Config.getServerPort());

            // Create separate streams for text commands and binary data
            textReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            textWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataInputStream = new DataInputStream(socket.getInputStream());
            dataOutputStream = new DataOutputStream(socket.getOutputStream());

            textWriter.write("CLIENT_ID " + clientId + "\n");
            textWriter.flush();

            // Thread to handle incoming text messages
            new Thread(() -> {
                try {
                    String line;
                    while ((line = textReader.readLine()) != null) {
                        final String message = line;
                        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
                    }
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> logArea.append("[ERROR] Connection lost.\n"));
                    logger.log(Logger.Level.ERROR, "ClientGUI", "Connection lost", e);
                }
            }).start();

        } catch (IOException e) {
            showError("Failed to connect to server: " + e.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Failed to connect to server", e);
        }
    }

    private void handleUpload(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        try {
            // Send upload command
            textWriter.write("UPLOAD " + file.getName() + "\n");
            textWriter.flush();
            logArea.append("[INFO] Sending upload command for: " + file.getName() + "\n");

            // Send file size
            dataOutputStream.writeLong(file.length());

            // Calculate and send checksum
            byte[] checksum = calculateChecksum(file);
            dataOutputStream.writeInt(checksum.length);
            dataOutputStream.write(checksum);

            // Send file data
            try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    dataOutputStream.write(buffer, 0, count);
                }
            }
            dataOutputStream.flush();
            logArea.append("[INFO] Uploaded " + file.getName() + " from folder: " + file.getParent() + "\n");
        } catch (IOException ex) {
            showError("Upload failed: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Upload failed", ex);
        } catch (Exception ex) {
            showError("Error calculating checksum: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Checksum calculation failed", ex);
        }
    }

    private void handleDownload(ActionEvent e) {
        String filename = downloadField.getText().trim();
        if (filename.isEmpty()) return;

        try {
            // Clear the input stream buffer before starting
            while (textReader.ready()) textReader.readLine();

            // Send download command
            textWriter.write("DOWNLOAD " + filename + "\n");
            textWriter.flush();
            logArea.append("[INFO] Requesting download of: " + filename + "\n");

            // Receive file size
            long size = dataInputStream.readLong();

            if (size <= 0) {
                logArea.append("[ERROR] Server reported invalid or missing file.\n");
                return;
            }

            logArea.append("[INFO] Receiving file: " + filename + " (" + size + " bytes)\n");

            // Receive checksum
            int checksumLength = dataInputStream.readInt();
            byte[] expectedChecksum = new byte[checksumLength];
            dataInputStream.readFully(expectedChecksum);

            // Create download directory if it doesn't exist
            File dir = new File("downloads");
            dir.mkdirs();
            File outFile = new File(dir, filename);

            // Receive file data with progress reporting
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                byte[] buffer = new byte[4096];
                long remaining = size;
                int count;
                long lastProgressUpdate = 0;
                while (remaining > 0 &&
                        (count = dataInputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, count);
                    remaining -= count;

                    // Update progress every ~10%
                    long progressPoint = size / 10;
                    if (progressPoint > 0 && (size - remaining) / progressPoint > lastProgressUpdate) {
                        lastProgressUpdate = (size - remaining) / progressPoint;
                        final int percentage = (int)(100 * (size - remaining) / (double)size);
                        SwingUtilities.invokeLater(() ->
                                logArea.append("[INFO] Download progress: " + percentage + "%\n"));
                    }
                }
                fos.flush();
            }

            // Verify checksum
            byte[] actualChecksum = calculateChecksum(outFile);
            boolean checksumMatch = MessageDigest.isEqual(expectedChecksum, actualChecksum);

            if (!checksumMatch) {
                logArea.append("[ERROR] Downloaded file is corrupted. Checksum verification failed.\n");
                outFile.delete(); // Delete corrupted file
                return;
            }

            logArea.append("[INFO] Downloaded " + filename + " to folder: downloads/\n");

            // Start a separate thread to wait for the response message without blocking the UI
            new Thread(() -> {
                try {
                    String response = textReader.readLine();
                    if (response != null) {
                        SwingUtilities.invokeLater(() -> logArea.append("[SERVER] " + response + "\n"));
                    }
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> logArea.append("[ERROR] Failed to read server response\n"));
                    logger.log(Logger.Level.ERROR, "ClientGUI", "Failed to read server response", ex);
                }
            }).start();

        } catch (IOException ex) {
            showError("Download failed: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Download failed", ex);
        } catch (Exception ex) {
            showError("Error verifying download: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Checksum verification failed", ex);
        }
    }

    private void handleListFiles(ActionEvent e) {
        try {
            textWriter.write("LIST\n");
            textWriter.flush();
            logArea.append("[INFO] Requesting file list from server...\n");
        } catch (IOException ex) {
            showError("Failed to send LIST command: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Failed to send LIST command", ex);
        }
    }

    private void handleViewLogs(ActionEvent e) {
        try (Connection conn = DriverManager.getConnection(Config.getDbUrl());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 50")) {

            logArea.append("=== File Logs (Last 50 Actions) ===\n");
            boolean hasLogs = false;
            while (rs.next()) {
                hasLogs = true;
                logArea.append("[" + rs.getString("timestamp") + "] " +
                        rs.getString("client") + " " +
                        rs.getString("action") + ": " +
                        rs.getString("filename") + "\n");
            }
            if (!hasLogs) {
                logArea.append("No logs found.\n");
            }

        } catch (SQLException ex) {
            showError("Database Error: " + ex.getMessage());
            logger.log(Logger.Level.ERROR, "ClientGUI", "Database error while viewing logs", ex);
        }
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
        logArea.append("[ERROR] " + msg + "\n");
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }
}