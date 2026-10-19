import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Distributed Chat Application - Java Swing client.
 * Run: java ChatClient [server-host] [port]
 */
public class ChatClient extends JFrame {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5050;

    private final JTextField hostField = new JTextField(DEFAULT_HOST, 11);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 5);
    private final JTextField usernameField = new JTextField(12);
    private final JTextArea chatArea = new JTextArea();
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JTextField messageField = new JTextField();
    private final JCheckBox privateCheck = new JCheckBox("Send privately");
    private final JButton connectButton = new JButton("Connect");
    private final JButton sendButton = new JButton("Send");
    private final JLabel statusLabel = new JLabel("Not connected");

    private volatile boolean connected = false;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;

    public ChatClient(String initialHost, int initialPort) {
        super("Distributed Chat Application");
        hostField.setText(initialHost);
        portField.setText(String.valueOf(initialPort));
        buildUI();
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); dispose(); }
        });
        setSize(880, 570);
        setMinimumSize(new Dimension(680, 430));
        setLocationRelativeTo(null);
    }

    private void buildUI() {
        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 8));
        connectionPanel.add(new JLabel("Server IP:"));
        connectionPanel.add(hostField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(portField);
        connectionPanel.add(new JLabel("Username:"));
        connectionPanel.add(usernameField);
        connectionPanel.add(connectButton);

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JScrollPane chatScroll = new JScrollPane(chatArea);

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel rightPanel = new JPanel(new BorderLayout(4, 6));
        rightPanel.setPreferredSize(new Dimension(180, 300));
        rightPanel.add(new JLabel("Online users"), BorderLayout.NORTH);
        rightPanel.add(new JScrollPane(userList), BorderLayout.CENTER);
        rightPanel.add(privateCheck, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        center.add(chatScroll, BorderLayout.CENTER);
        center.add(rightPanel, BorderLayout.EAST);

        JPanel sendPanel = new JPanel(new BorderLayout(8, 0));
        sendPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        sendPanel.add(messageField, BorderLayout.CENTER);
        sendPanel.add(sendButton, BorderLayout.EAST);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(238, 238, 238));

        setLayout(new BorderLayout(0, 0));
        add(connectionPanel, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(sendPanel, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        sendButton.setEnabled(false);
        connectButton.addActionListener(e -> connect());
        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        getRootPane().setDefaultButton(sendButton);
    }

    private void connect() {
        if (connected) return;
        String username = usernameField.getText().trim();
        if (username.isEmpty() || username.length() > 20) {
            JOptionPane.showMessageDialog(this, "Enter a username of 1-20 characters.",
                    "Username required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            connected = true;
            sendButton.setEnabled(true);
            connectButton.setEnabled(false);
            statusLabel.setText("Connected to " + host + ":" + port + " as " + username);
            appendLine("Connecting as " + username + "...");
            sendPacket("LOGIN|" + cleanField(username));
            readerThread = new Thread(this::readMessages, "ChatClient-Reader");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Port must be a number.", "Invalid port", JOptionPane.ERROR_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not connect to server.\n" + ex.getMessage(),
                    "Connection failed", JOptionPane.ERROR_MESSAGE);
            disconnect();
        }
    }

    private String cleanField(String value) {
        return value.replace("|", "").replace("\r", "").replace("\n", "");
    }

    private void sendPacket(String packet) {
        PrintWriter writer = out;
        if (writer != null && connected) {
            writer.println(packet);
            if (writer.checkError()) {
                appendLine("Connection lost.", true);
                disconnect();
            }
        }
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (!connected || text.isEmpty()) return;
        if (text.length() > 2000) {
            JOptionPane.showMessageDialog(this, "Messages must be 2,000 characters or fewer.");
            return;
        }
        if (privateCheck.isSelected()) {
            String target = userList.getSelectedValue();
            if (target == null) {
                JOptionPane.showMessageDialog(this, "Select an online user for a private message.");
                return;
            }
            sendPacket("PRIVATE|" + cleanField(target) + "|" + cleanField(text));
        } else {
            sendPacket("MSG|" + cleanField(text));
        }
        messageField.setText("");
        messageField.requestFocusInWindow();
    }

    private void readMessages() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                final String packet = line;
                SwingUtilities.invokeLater(() -> handlePacket(packet));
            }
        } catch (IOException ignored) {
            // Server closed the connection.
        } finally {
            if (connected) SwingUtilities.invokeLater(() -> {
                appendLine("Disconnected from server.", true);
                disconnect();
            });
        }
    }

    private void handlePacket(String line) {
        String[] parts = line.split("\\|", 5);
        switch (parts[0]) {
            case "LOGIN_OK":
                appendLine("You are connected. Group messages are visible to all users.");
                break;
            case "LOGIN_ERROR":
                appendLine(parts.length > 1 ? parts[1] : "Login failed.", true);
                JOptionPane.showMessageDialog(this, parts.length > 1 ? parts[1] : "Login failed.",
                        "Login failed", JOptionPane.ERROR_MESSAGE);
                disconnect();
                break;
            case "USERS":
                userModel.clear();
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    for (String name : parts[1].split(",")) userModel.addElement(name);
                }
                break;
            case "SYSTEM":
                if (parts.length >= 3) appendLine("[" + parts[1] + "] * " + parts[2]);
                break;
            case "MESSAGE":
                if (parts.length >= 4) appendLine("[" + parts[1] + "] " + parts[2] + ": " + parts[3]);
                break;
            case "PRIVATE":
                if (parts.length >= 5) {
                    appendLine("[" + parts[1] + "] [PRIVATE] " + parts[2] + " -> " + parts[3] + ": " + parts[4], true);
                }
                break;
            case "ERROR":
                appendLine("Error: " + (parts.length > 1 ? parts[1] : "Unknown error"), true);
                break;
            default:
                appendLine(line);
        }
    }

    private void appendLine(String text) { appendLine(text, false); }

    private void appendLine(String text, boolean highlight) {
        chatArea.append(text + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
        if (highlight) chatArea.setForeground(new Color(95, 45, 125));
        else chatArea.setForeground(Color.BLACK);
    }

    private void disconnect() {
        connected = false;
        sendButton.setEnabled(false);
        connectButton.setEnabled(true);
        statusLabel.setText("Not connected");
        if (out != null) out.close();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (args.length > 1) {
            try { port = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) { System.err.println("Invalid port; using " + DEFAULT_PORT); }
        }
        final int finalPort = port;
        SwingUtilities.invokeLater(() -> new ChatClient(host, finalPort).setVisible(true));
    }
}
