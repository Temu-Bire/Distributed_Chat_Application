import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed Chat Application - Java TCP server.
 * Run: java ChatServer [port]
 */
public class ChatServer {
    private static final int DEFAULT_PORT = 5050;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Map<ClientHandler, String> clients = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    private static String timeNow() {
        return LocalTime.now().format(CLOCK);
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port. Usage: java ChatServer [port]");
                return;
            }
        }
        new ChatServer().start(port);
    }

    public void start(int port) {
        try (ServerSocket server = new ServerSocket(port)) {
            serverSocket = server;
            System.out.println("Chat server listening on port " + port);
            System.out.println("Press Ctrl+C to stop.");
            while (running) {
                Socket socket = server.accept();
                System.out.println("Connection from " + socket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(socket);
                handler.start();
            }
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. Stop the other server or choose another port.");
        } catch (SocketException e) {
            if (running) System.err.println("Server socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            running = false;
            for (ClientHandler client : new ArrayList<>(clients.keySet())) {
                client.closeConnection(false);
            }
            System.out.println("Server stopped.");
        }
    }

    private synchronized boolean register(ClientHandler handler, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty() || name.length() > 20 || name.contains("\n") || name.contains("\r")) {
            handler.send("LOGIN_ERROR|Username must contain 1-20 characters.");
            return false;
        }
        for (String existing : clients.values()) {
            if (existing.equalsIgnoreCase(name)) {
                handler.send("LOGIN_ERROR|That username is already in use.");
                return false;
            }
        }
        clients.put(handler, name);
        handler.username = name;
        handler.send("LOGIN_OK|" + name);
        handler.send("USERS|" + String.join(",", getUserNames()));
        broadcast("SYSTEM|" + timeNow() + "|" + name + " joined the chat.", handler);
        broadcastUsers();
        System.out.println(name + " logged in.");
        return true;
    }

    private List<String> getUserNames() {
        List<String> names = new ArrayList<>(clients.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void broadcastUsers() {
        String payload = "USERS|" + String.join(",", getUserNames());
        for (ClientHandler client : clients.keySet()) client.send(payload);
    }

    private void broadcast(String message, ClientHandler exclude) {
        for (ClientHandler client : clients.keySet()) {
            if (client != exclude) client.send(message);
        }
    }

    private void remove(ClientHandler handler, boolean announce) {
        String name = clients.remove(handler);
        handler.closeSocketOnly();
        if (name != null) {
            System.out.println(name + " disconnected.");
            if (announce) broadcast("SYSTEM|" + timeNow() + "|" + name + " left the chat.", handler);
            broadcastUsers();
        }
    }

    private class ClientHandler extends Thread {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private volatile String username;
        private volatile boolean open = true;

        ClientHandler(Socket socket) {
            this.socket = socket;
            setName("ClientHandler-" + socket.getPort());
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String line;
                while (open && (line = in.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException ignored) {
                // The client may close the connection at any time.
            } finally {
                remove(this, true);
            }
        }

        private void handleLine(String line) {
            String[] parts = line.split("\\|", 3);
            String type = parts[0];
            if ("LOGIN".equals(type)) {
                if (username == null) register(this, parts.length > 1 ? parts[1] : "");
                return;
            }
            if (username == null) {
                send("ERROR|Please log in first.");
                return;
            }
            if ("MSG".equals(type)) {
                String text = parts.length > 1 ? parts[1] : "";
                if (text.trim().isEmpty()) return;
                if (text.length() > 2000) {
                    send("ERROR|Messages must be 2,000 characters or fewer.");
                    return;
                }
                broadcast("MESSAGE|" + timeNow() + "|" + username + "|" + text, null);
            } else if ("PRIVATE".equals(type)) {
                if (parts.length < 3) {
                    send("ERROR|Private message format is invalid.");
                    return;
                }
                String targetName = parts[1].trim();
                String text = parts[2];
                if (text.trim().isEmpty()) return;
                if (text.length() > 2000) {
                    send("ERROR|Messages must be 2,000 characters or fewer.");
                    return;
                }
                ClientHandler target = null;
                for (Map.Entry<ClientHandler, String> entry : clients.entrySet()) {
                    if (entry.getValue().equalsIgnoreCase(targetName)) {
                        target = entry.getKey();
                        break;
                    }
                }
                if (target == null) {
                    send("ERROR|User '" + targetName + "' is not online.");
                } else {
                    String packet = "PRIVATE|" + timeNow() + "|" + username + "|" + targetName + "|" + text;
                    target.send(packet);
                    if (target != this) send(packet);
                }
            }
        }

        synchronized void send(String message) {
            if (out != null && open) {
                out.println(message);
                if (out.checkError()) closeConnection(true);
            }
        }

        void closeSocketOnly() {
            open = false;
            try { socket.close(); } catch (IOException ignored) {}
        }

        void closeConnection(boolean announce) {
            remove(this, announce);
        }
    }
}
