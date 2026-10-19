# Project Report Template: Distributed Chat Application (Java)

**Student:** Sintayehu Shenkute  
**Student ID:** DBU1405907  
**Course:** Introduction to Distributed Systems  
**Assignment:** Individual Assignment I  
**Topic:** Distributed Chat Application

> Complete this report after compiling and testing the application. Add your own screenshots and observed results; do not claim tests passed unless you ran them.

## 1. Abstract
This project develops a distributed chat application using Java TCP sockets and multithreading. A server accepts concurrent client connections and routes group or private messages. A Swing graphical user interface allows users to connect, chat, and view online users.

## 2. Introduction
A distributed system contains independent processes that communicate through a network. A chat application demonstrates client-server communication because several clients can exchange messages through a shared server.

## 3. Objectives
- Implement communication using `ServerSocket` and `Socket`.
- Handle multiple client connections concurrently with threads.
- Support group and private messaging.
- Show connected users and connection notifications.
- Demonstrate shared-state management and disconnection handling.

## 4. Tools and technologies
- Java Development Kit (JDK)
- TCP sockets: `ServerSocket`, `Socket`
- Concurrency: `Thread`, `ConcurrentHashMap`
- GUI: Java Swing
- Protocol: line-based text messages

## 5. System architecture
The server listens on a port and accepts incoming client connections. Each connection is served by a separate `ClientHandler` thread. Clients send login, group-message, or private-message packets. The server broadcasts group messages or forwards private messages to the target user.

## 6. Implementation details
Explain:
- Server socket creation, listening, and accepting connections.
- The `ClientHandler` thread and how it reads client messages.
- The shared map of connected clients.
- Group broadcast and private-message routing.
- The Swing GUI and background thread used to receive messages.

## 7. Testing and results
Fill in the actual results after running each test.

| Test case | Expected result | Actual result |
|---|---|---|
| Compile Java source | No compilation errors | [Fill after test] |
| Start server | Server listens on port 5050 | [Fill after test] |
| Connect multiple clients | Each unique username can connect | [Fill after test] |
| Group message | All online clients receive message | [Fill after test] |
| Private message | Selected recipient receives private message | [Fill after test] |
| Duplicate username | Duplicate login is rejected | [Fill after test] |
| Client disconnect | Online-user list is updated | [Fill after test] |

Add screenshots of the server console and at least two client windows.

## 8. Security and limitations
The application is intended for a local educational demonstration. It does not provide encrypted transport, password authentication, or persistent message history. Do not expose it to an untrusted network without improving security.

## 9. Conclusion
Summarize the features you implemented, the results of your tests, and what you learned about TCP sockets and multithreading.

## 10. Future enhancements
Possible improvements include TLS encryption, account authentication, chat rooms, database-backed history, file sharing, and automated tests.

## References
- Oracle Java documentation for `java.net`, `java.io`, `java.lang.Thread`, and Swing.
- Course lecture notes on distributed systems.
