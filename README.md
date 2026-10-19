# Distributed Chat Application (Java 21 + Spring Boot 3)

A high-performance, Telegram-inspired distributed messaging platform built with **Java 21**, **Spring Boot 3**, **WebSocket / STOMP**, **Spring Data JPA**, **PostgreSQL**, **Redis**, and a clean **HTML5/CSS3/Vanilla JS** frontend.

The application evolved from an initial single-process Java TCP socket and Swing assignment into an enterprise-grade distributed system supporting user accounts, direct and group conversations, message CRUD, cluster-wide presence, and cross-instance messaging across load-balanced nodes.

> **Legacy Preservation:** The original TCP socket and Swing client sources are preserved intact under [`legacy/tcp/`](legacy/tcp/). See [The original TCP version](#the-original-tcp-version).

---

## Table of Contents

1. [Features](#features)
2. [Architecture Overview](#architecture-overview)
3. [Prerequisites](#prerequisites)
4. [How to Run the Application](#how-to-run-the-application)
   - [Mode 1: Quick Start (Single Node Development)](#mode-1-quick-start-single-node-development)
   - [Mode 2: Distributed Multi-Instance Cluster (Docker Compose)](#mode-2-distributed-multi-instance-cluster-docker-compose)
   - [Mode 3: Local Development with PostgreSQL and Redis](#mode-3-local-development-with-postgresql-and-redis)
   - [Mode 4: Running the Legacy TCP Version](#mode-4-running-the-legacy-tcp-version)
5. [How to Test the Application Fully](#how-to-test-the-application-fully)
   - [Automated Test Suite (81 Tests)](#automated-test-suite-81-tests)
   - [Running Specific Test Classes](#running-specific-test-classes)
   - [Manual Testing Walkthrough (Two Browsers)](#manual-testing-walkthrough-two-browsers)
   - [Testing Distributed Multi-Instance Messaging](#testing-distributed-multi-instance-messaging)
6. [API & Messaging Contract Reference](#api--messaging-contract-reference)
   - [REST API Endpoints](#rest-api-endpoints)
   - [WebSocket / STOMP Destinations](#websocket--stomp-destinations)
7. [Environment & Configuration Reference](#environment--configuration-reference)
8. [Project Structure](#project-structure)

---

## Features

### 🎨 Telegram-Inspired Modern Frontend
- **Design System:** Dark navy / charcoal palette, violet accents, crisp typography, and fluid CSS transitions.
- **Light / Dark Theme:** One-click theme toggle with immediate UI recoloring.
- **Responsive Layout:** Side-by-side desktop view and mobile drawer navigation with backdrop tap-to-close.
- **Message Bubbles:** Incoming (left, purple avatar) vs. outgoing (right, blue accent, double checkmarks `✓✓`).
- **Real-Time Elements:** Live user search filter, online count badges, typing character counter (`0/2000`), quick emoji picker, and toast alerts.
- **Security-First:** 100% safe DOM rendering using `textContent` (zero `innerHTML` sinks, verified by automated tests).

### 🔐 User Accounts & Authentication
- **User Registration:** Display name, unique username, unique email, and password.
- **Secure Password Hashing:** BCrypt password encryption (cost factor 10).
- **Session-Based Authentication:** Standard Spring Security session management with cookie handling.
- **Profile Management:** Update bio, display name, avatar URL, or change password with old-password verification.
- **User Search & Discovery:** Search active registered users by username or display name with pagination.

### 💬 Persistent Conversations & Group Chats
- **Direct 1-on-1 Chats:** Start private chats with any registered user. Idempotent creation prevents duplicates and automatically unarchives inactive chats.
- **Multi-Member Group Chats:** Create groups with custom titles, descriptions, and avatars.
- **Role-Based Group Administration:** Group creator receives `OWNER` role; owners can update group details, add members, or remove members.
- **Conversation Controls:** Archive / unarchive conversations and mark conversations as read.

### 📝 Persistent Message Lifecycle & History
- **Relational Storage:** Messages stored in PostgreSQL via Spring Data JPA with Flyway schema migration.
- **Chronological Pagination:** Retrieve conversation history in ordered pages.
- **Message Editing:** Authors can edit their own sent messages with real-time `edited` indicator.
- **Soft Deletion:** Authors or group owners can delete messages; content is replaced with `"This message was deleted."` while preserving thread continuity.
- **STOMP Broadcasts:** Dynamic conversation events (`NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED`) pushed to subscribers on `/topic/conversations/{id}`.

### 🌐 Distributed Multi-Instance Architecture
- **Distributed Presence:** `RedisPresenceStore` tracks online users across multiple application nodes with TTL-based heartbeats to prevent ghost sessions.
- **Cross-Instance Message Relay:** `DistributedMessageRelay` uses Redis Pub/Sub to forward messages across cluster instances.
- **Load Balancing:** Nginx reverse proxy with IP hash load balancing and WebSocket upgrade support.
- **Single-Node Fallback:** Seamless fallback to lock-free `InMemoryPresenceStore` when running standalone without Redis.

---

## Architecture Overview

```
                          +------------------------+
                          |   Web Browser Clients  |
                          +-----------+------------+
                                      | HTTP / WebSocket
                                      v
                          +------------------------+
                          |   Nginx Load Balancer  |
                          |     (Port 80 / TCP)    |
                          +-----+------------+-----+
                                |            |
                  HTTP/WS Proxy |            | HTTP/WS Proxy
                                v            v
                    +-----------+--+      +--+-----------+
                    |  Chat App 1  |      |  Chat App 2  |
                    | (Port 8081)  |      | (Port 8082)  |
                    +---+------+---+      +---+------+---+
                        |      |              |      |
           Redis Pub/Sub|      |JPA / SQL     |      |
           & Presence   |      |              |      |
                        v      v              v      v
                 +---------+  +------------------+
                 |  Redis  |  |    PostgreSQL    |
                 | Cluster |  | Relational Store |
                 +---------+  +------------------+
```

---

## Prerequisites

- **Java Development Kit (JDK):** Version 21 (LTS) or higher.
- **Maven:** No separate installation required. The included Maven Wrapper (`mvnw` / `mvnw.cmd`) downloads Maven automatically.
- **Docker & Docker Compose (Optional):** Required only for running the multi-container distributed cluster.

Set `JAVA_HOME` in PowerShell if not already configured:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" # or your JDK 21 path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -v
```

---

## How to Run the Application

### Mode 1: Quick Start (Single Node Development)

Run the application immediately with zero external dependencies. Uses an embedded in-memory database and lock-free memory store:

```powershell
.\mvnw.cmd spring-boot:run
```

Once you see `Started ChatApplication`, open **<http://localhost:8080>** in your browser.

> **Port Conflict:** If port 8080 is occupied, run on another port:
> ```powershell
> .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8090"
> ```

---

### Mode 2: Distributed Multi-Instance Cluster (Docker Compose)

Launch the complete distributed topology (PostgreSQL, Redis, two chat instances, and an Nginx reverse proxy):

```powershell
# Build and start all 5 containers in the background
docker compose up --build -d
```

#### Services started:
| Service | Container Name | Port Mapping | Description |
|---|---|---|---|
| **Nginx** | `chat_nginx` | `80:80` | Entry point & load balancer |
| **Chat App 1** | `chat_app1` | `8081:8080` | Spring Boot Instance 1 |
| **Chat App 2** | `chat_app2` | `8082:8080` | Spring Boot Instance 2 |
| **PostgreSQL** | `chat_postgres` | `5432:5432` | Relational storage |
| **Redis** | `chat_redis` | `6379:6379` | Shared presence & Pub/Sub |

Access the load-balanced cluster at **<http://localhost>**.

To view cluster logs or stop containers:
```powershell
docker compose logs -f
docker compose down
```

---

### Mode 3: Local Development with PostgreSQL and Redis

If you run PostgreSQL and Redis locally (e.g. via Homebrew or Docker) and want to run the Spring Boot app directly:

```powershell
# Copy environment file template
Copy-Item .env.example .env

# Run with distributed mode enabled
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/chat_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "postgres"
$env:SPRING_FLYWAY_ENABLED = "true"
$env:SPRING_REDIS_HOST = "localhost"
$env:SPRING_REDIS_PORT = "6379"
$env:CHAT_DISTRIBUTED_ENABLED = "true"

.\mvnw.cmd spring-boot:run
```

---

### Mode 4: Running the Legacy TCP Version

The original TCP socket server and Swing GUI client assignment are preserved in [`legacy/tcp/`](legacy/tcp/):

```powershell
cd legacy\tcp
& "$env:JAVA_HOME\bin\javac.exe" ChatServer.java ChatClient.java

# Terminal 1: Start TCP Server
& "$env:JAVA_HOME\bin\java.exe" ChatServer

# Terminal 2: Start First Swing Client
& "$env:JAVA_HOME\bin\java.exe" ChatClient

# Terminal 3: Start Second Swing Client
& "$env:JAVA_HOME\bin\java.exe" ChatClient
```

---

## How to Test the Application Fully

### Automated Test Suite (81 Tests)

Run all 81 automated tests with Maven:

```powershell
.\mvnw.cmd test
```

### Test Suite Breakdown:

| Test Class | Tests | What is Tested |
|---|---|---|
| **`AuthServiceTest`** | 8 | User registration, password encryption, duplicate username rejection (case-insensitive), duplicate email rejection, login, profile updates, password change, user search |
| **`ChatServiceTest`** | 27 | STOMP join rules, duplicate name rejection, message length validation (max 2000 chars), blank content rejection, public broadcast, private message routing, offline recipient error, disconnection cleanup |
| **`DuplicateUsernameConcurrencyTest`** | 3 | High-concurrency race condition testing: 32 threads simultaneously racing to claim the same display name; asserts exactly one wins |
| **`ConversationServiceTest`** | 8 | Direct conversation creation, idempotency, self-chat prevention, group creation with owner role, member additions and removals, RBAC permissions, voluntary leave, archiving |
| **`PersistentMessageServiceTest`** | 7 | Message persistence, conversation summary updates, size validation, non-member rejection, chronological pagination, author edit verification, unauthorized edit rejection, soft-delete filtering |
| **`FrontendContractTest`** | 2 | Verifies `chat.js` pins exactly the server's STOMP destinations (`MessageRouter`) and contains zero `innerHTML` / `outerHTML` sinks |
| **`InMemoryPresenceStoreTest`** | 11 | Presence storage contract, case-insensitivity, thread-safe register/unregister atomicity |
| **`HealthEndpointIntegrationTest`** | 2 | HTTP GET `/api/health` integration test verifying health status reporting |
| **`ChatWebSocketIntegrationTest`** | 13 | Real WebSocket/STOMP frames with 2-3 simultaneous clients: join/leave notifications, private delivery isolation, sender spoofing prevention, payload limits |
| **Total** | **81** | **100% Passing (0 failures, 0 errors, 0 skipped)** |

### Running Specific Test Classes

```powershell
# Run only conversation and message persistence tests:
.\mvnw.cmd test "-Dtest=ConversationServiceTest,PersistentMessageServiceTest"

# Run only authentication tests:
.\mvnw.cmd test "-Dtest=AuthServiceTest"

# Run only end-to-end WebSocket tests:
.\mvnw.cmd test "-Dtest=ChatWebSocketIntegrationTest"
```

---

### Manual Testing Walkthrough (Two Browsers)

1. **Start the application:**
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
2. **Open Window 1 (Alice):**
   - Navigate to `http://localhost:8080`.
   - Enter `alice` on the welcome screen and click **Connect to Chat**.
   - Verify: Status chip turns green `● Connected`, sidebar shows avatar `A`, and header indicates `Public Chat · 1 online`.
3. **Open Window 2 (Bob in Incognito/Second Browser):**
   - Navigate to `http://localhost:8080`.
   - Enter `bob` and click **Connect to Chat**.
   - Verify: Both windows update user count to `Online Users (2)` with a subtle notification.
4. **Public Messaging:**
   - In Alice's window, type `Hello everyone!` and press Enter.
   - Verify: Alice sees her message on the right in blue with checkmarks `✓✓`. Bob sees the message on the left with Alice's avatar and timestamp.
5. **Direct Messaging:**
   - In Bob's window, click on `alice` in the sidebar list.
   - Conversation header switches to `@alice · Direct message · Online`.
   - Send `Hey Alice, this is private`.
   - Verify: The direct message appears only in Bob's view and Alice's view. It never appears on the public channel.
6. **Theme Switching:**
   - Click the Sun/Moon icon in the top header. The interface seamlessly transitions between dark and light modes.

---

### Testing Distributed Multi-Instance Messaging

To verify that two users connected to **two different application instances** can communicate across the cluster:

1. **Start the Docker cluster:**
   ```powershell
   docker compose up --build -d
   ```
2. **Open Alice directly on Instance 1:**
   - Open browser at `http://localhost:8081`.
   - Join as `alice`.
3. **Open Bob directly on Instance 2:**
   - Open browser at `http://localhost:8082`.
   - Join as `bob`.
4. **Observe Distributed Synchronization:**
   - In Instance 1, Alice sees `bob` listed as online.
   - Send a message from `alice` on Instance 1: Bob receives it instantly on Instance 2 through Redis Pub/Sub relay!
   - Send a direct message from `bob` on Instance 2 to `alice`: delivered directly across instances.

---

## API & Messaging Contract Reference

### REST API Endpoints

#### Authentication (`/api/auth`)
| Method | Path | Description | Access |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register new user account | Public |
| `POST` | `/api/auth/login` | Login with username/email & password | Public |
| `POST` | `/api/auth/logout` | Terminate session | Authenticated |
| `GET` | `/api/auth/me` | Current authenticated user profile | Authenticated |
| `GET` | `/api/auth/status` | Current authentication state | Public |

#### User Management (`/api/users`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/users/search?query=...` | Search users by name | Authenticated |
| `PUT` | `/api/users/profile` | Update profile bio and display name | Authenticated |
| `PUT` | `/api/users/password` | Change user password | Authenticated |

#### Conversations (`/api/conversations`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/conversations` | Get user's active conversations | Authenticated |
| `POST` | `/api/conversations/direct` | Get or create direct conversation | Authenticated |
| `POST` | `/api/conversations/group` | Create new group conversation | Authenticated |
| `GET` | `/api/conversations/{id}` | Get conversation details | Member |
| `GET` | `/api/conversations/{id}/members` | List members of conversation | Member |
| `POST` | `/api/conversations/{id}/members` | Add member to group | Member / Owner |
| `DELETE` | `/api/conversations/{id}/members/{userId}` | Remove member or leave group | Member / Owner |
| `PUT` | `/api/conversations/{id}` | Update group title and description | Owner only |
| `DELETE` | `/api/conversations/{id}` | Delete group conversation | Owner only |
| `PUT` | `/api/conversations/{id}/archive` | Archive or unarchive conversation | Member |
| `PUT` | `/api/conversations/{id}/read` | Update last read timestamp | Member |

#### Messages (`/api/conversations/{id}/messages`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/conversations/{id}/messages` | Paginate messages (`page`, `size`) | Member |
| `POST` | `/api/conversations/{id}/messages` | Send message to conversation | Member |
| `PUT` | `/api/conversations/{id}/messages/{msgId}` | Edit existing message | Author only |
| `DELETE` | `/api/conversations/{id}/messages/{msgId}` | Soft delete message | Author / Owner |

#### Health (`/api/health`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/health` | Health status and uptime | Public |

---

### WebSocket / STOMP Destinations

| Direction | Destination | Purpose | Payload |
|---|---|---|---|
| **Client → Server** | `/app/chat.join` | Join chat and register presence | `{"username": "alice"}` |
| | `/app/chat.send` | Send public message | `{"content": "Hello!"}` |
| | `/app/chat.private` | Send private message | `{"recipient": "bob", "content": "Hi!"}` |
| **Server → Client** | `/topic/public` | Public messages broadcast | `ChatMessage` object |
| | `/topic/users` | Presence changes & online list | User list & join/leave event |
| | `/user/queue/private` | Direct message inbox | `ChatMessage` (recipient/sender only) |
| | `/user/queue/errors` | Error notifications | `{"code": "...", "message": "..."}` |
| | `/topic/conversations/{id}` | Persistent conversation events | `NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED` |

---

## Environment & Configuration Reference

Configure via environment variables or in `src/main/resources/application.yml`:

| Environment Variable | Default Value | Description |
|---|---|---|
| `PORT` | `8080` | HTTP and WebSocket server port |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/chat_db` | PostgreSQL JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | PostgreSQL password |
| `SPRING_FLYWAY_ENABLED` | `false` (local) / `true` (prod/test) | Enable Flyway automatic migrations |
| `SPRING_REDIS_HOST` | `localhost` | Redis server hostname |
| `SPRING_REDIS_PORT` | `6379` | Redis server port |
| `CHAT_DISTRIBUTED_ENABLED` | `false` | Enable Redis cluster presence & Pub/Sub relay |

---

## Project Structure

```
.
├── Dockerfile                          # Multi-stage Docker build
├── docker-compose.yml                  # Postgres, Redis, 2 App nodes, Nginx
├── nginx/
│   └── nginx.conf                      # Reverse proxy & WebSocket load balancing
├── pom.xml                             # Spring Boot 3.5.16, Java 21, JPA, Security, Redis
├── README.md                           # This documentation
├── REPORT_TEMPLATE.md                  # Comprehensive academic/technical project report
├── docs/
│   └── architecture.md                 # Architecture diagrams, decisions, and layers
├── legacy/
│   └── tcp/                            # Preserved original TCP ChatServer & ChatClient
└── src/
    ├── main/
    │   ├── java/com/example/chat/
    │   │   ├── ChatApplication.java    # Application entry point
    │   │   ├── api/                    # REST Controllers (Auth, Users, Convs, Messages)
    │   │   ├── application/            # Business services (Auth, Conv, Message, Presence)
    │   │   ├── config/                 # Security, WebSocket, Distributed configuration
    │   │   ├── domain/                 # Domain entities and value objects
    │   │   ├── dto/                    # Request/Response data transfer objects
    │   │   ├── exception/              # Global error handling and ChatException
    │   │   ├── infrastructure/         # Repositories, InMemory and Redis Presence Stores
    │   │   └── websocket/              # STOMP controllers and connection event listeners
    │   └── resources/
    │       ├── application.yml         # Application configuration
    │       ├── db/migration/           # Flyway SQL schema migrations
    │       └── static/                 # Telegram-inspired vanilla web frontend
    │           ├── index.html          # Chat layout and modals
    │           ├── css/styles.css      # CSS design tokens, themes, bubbles
    │           └── js/
    │               ├── chat.js         # Frontend controller (100% XSS safe)
    │               └── vendor/         # @stomp/stompjs library
    └── test/java/com/example/chat/
        ├── application/                # Service & concurrency race tests
        ├── frontend/                   # Contract & XSS safety tests
        ├── infrastructure/             # Presence store tests
        ├── integration/                # REST integration tests
        └── websocket/                  # Full-duplex WebSocket STOMP integration tests
```
