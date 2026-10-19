# Distributed Chat Application (Java JDK)

**Assignment topic:** No. 23 — Distributed Chat Application  
**Course:** Introduction to Distributed Systems  
**Technologies:** Java TCP sockets, multithreading, Swing GUI

## Requirements
- JDK 17 or newer recommended (JDK 8+ should also work with this source).
- No third-party libraries required.

## Project structure
```
Distributed_Chat_Application_Java/
├── src/
│   ├── ChatServer.java
│   └── ChatClient.java
├── README.md
└── REPORT_TEMPLATE.md
```

## Check that JDK is installed
Open PowerShell and run:
```powershell
java -version
javac -version
```
Both commands should print a version. If `javac` is not recognized, install a JDK (not only a JRE), then reopen PowerShell.

Official downloads:
- Eclipse Temurin: https://adoptium.net/
- Oracle Java downloads: https://www.oracle.com/java/technologies/downloads/

## Compile
Open PowerShell in the project folder, then:
```powershell
cd src
javac ChatServer.java ChatClient.java
```
If successful, `.class` files will be created in `src`.

## Run the server
In the same `src` folder:
```powershell
java ChatServer
```
The server listens on port 5050. You may choose another port:
```powershell
java ChatServer 5051
```

## Run clients
Open a second PowerShell window in the `src` folder:
```powershell
java ChatClient
```
Connect using server IP `127.0.0.1`, port `5050`, and a unique username. Open additional terminals and run `java ChatClient` again to test several users.

If using port 5051, use that port in the client GUI too.

## Test on multiple computers
1. Start `java ChatServer` on one computer.
2. Find that computer's local network IP address using `ipconfig`.
3. On client computers, run `java ChatClient SERVER_IP 5050`, or enter the server IP in the GUI.
4. Allow Java through the firewall / permit incoming TCP port 5050 on the server computer.
5. For safety, use only a trusted local network; this educational project does not encrypt traffic.

## Features
- Multi-client TCP server with a separate handler thread per client.
- Group messages and private messages.
- Online-user list and join/leave notifications.
- Duplicate username prevention.
- Java Swing GUI and background receive thread.

## Protocol overview
Each line is a plain-text packet with fields separated by `|`:
- `LOGIN|username`
- `MSG|message`
- `PRIVATE|recipient|message`
- Server events: `MESSAGE|time|sender|message`, `PRIVATE|time|sender|recipient|message`, `USERS|name1,name2`, `SYSTEM|time|event`

## Notes
This is an educational project. Messages are not encrypted and history is not persisted. Test it yourself, take screenshots, and make sure you can explain sockets, threads, the message protocol, and the synchronized/shared client list during your presentation.
