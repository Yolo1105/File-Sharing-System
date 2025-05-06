import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.sql.*;

public class ClientGUI extends JFrame {
    private JTextArea logArea;
    private JTextField downloadField;
    private JButton uploadButton, downloadButton, listButton, viewLogsButton;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String clientId;

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
            socket = new Socket("localhost", 12345);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            writer.write("CLIENT_ID " + clientId + "\n");
            writer.flush();

            new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logArea.append(line + "\n");
                    }
                } catch (IOException e) {
                    logArea.append("[ERROR] Connection lost.\n");
                }
            }).start();

        } catch (IOException e) {
            showError("Failed to connect to server: " + e.getMessage());
        }
    }

    private void handleUpload(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        try {
            writer.write("UPLOAD " + file.getName() + "\n");
            writer.flush();

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeLong(file.length());

            try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
            }
            out.flush();
            logArea.append("[INFO] Uploaded " + file.getName() + " from folder: " + file.getParent() + "\n");
        } catch (IOException ex) {
            showError("Upload failed: " + ex.getMessage());
        }
    }

    private void handleDownload(ActionEvent e) {
        String filename = downloadField.getText().trim();
        if (filename.isEmpty()) return;

        try {
            writer.write("DOWNLOAD " + filename + "\n");
            writer.flush();

            InputStream rawIn = socket.getInputStream();
            DataInputStream in = new DataInputStream(rawIn);
            long size = in.readLong();  // wait for file size

            if (size <= 0) {
                logArea.append("[ERROR] Server reported invalid or missing file.\n");
                return;
            }

            File dir = new File("downloads");
            dir.mkdirs();
            File outFile = new File(dir, filename);
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                byte[] buffer = new byte[4096];
                long remaining = size;
                int count;
                while (remaining > 0 &&
                        (count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, count);
                    remaining -= count;
                }
                fos.flush();
            }

            logArea.append("[INFO] Downloaded " + filename + " to folder: downloads/\n");

        } catch (IOException ex) {
            showError("Download failed: " + ex.getMessage());
        }
    }

    private void handleListFiles(ActionEvent e) {
        try {
            writer.write("LIST\n");
            writer.flush();
        } catch (IOException ex) {
            showError("Failed to send LIST command.");
        }
    }

    private void handleViewLogs(ActionEvent e) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:file_logs.db");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM logs ORDER BY timestamp DESC")) {

            logArea.append("=== File Logs ===\n");
            boolean hasLogs = false;
            while (rs.next()) {
                hasLogs = true;
                logArea.append("[" + rs.getString("timestamp") + "] " +
                        rs.getString("client") + " " +
                        rs.getString("action") + ": " +
                        rs.getString("filename") + "\n");
            }
            if (!hasLogs) {
                logArea.append("Nothing is shared yet.\n");
            }

        } catch (SQLException ex) {
            showError("DB Error: " + ex.getMessage());
        }
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
        logArea.append("[ERROR] " + msg + "\n");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }
}
