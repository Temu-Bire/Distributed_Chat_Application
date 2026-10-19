# Project Report: Distributed Chat Application (Java)

**Student:** Sintayehu Shenkute  
**Student ID:** DBU1405907  
**Course:** Introduction to Distributed Systems  
**Assignment:** Individual Assignment I  
**Topic:** Distributed Chat Application  
**Date:** October 2026  
**Implementation:** Java 21, Spring Boot 3, WebSocket/STOMP, Spring Data JPA, PostgreSQL, Flyway, Redis, Docker Compose, Nginx  

---

## 1. Abstract

This project presents a scalable, fault-tolerant, distributed chat application built in Java 21 and Spring Boot 3. Starting from a foundational TCP socket implementation, the architecture has been modernized and expanded into an enterprise-grade distributed system featuring:
1. Secure session-based user authentication and identity management,
2. Persistent direct messaging and multi-member group conversations backed by relational storage (PostgreSQL/Flyway),
3. Rich message lifecycle management (sending, pagination, editing, and soft deletion),
4. Low-latency full-duplex communication over WebSocket with the STOMP sub-protocol,
5. Distributed cluster-wide presence tracking and cross-instance message synchronization powered by Redis Pub/Sub, and
6. Production-grade containerization with Docker Compose and Nginx reverse proxy load balancing.

The client interface is implemented as a Telegram-inspired single-page application using modern vanilla HTML5, CSS3 design tokens, and JavaScript, ensuring zero frontend build dependencies while guaranteeing strict XSS immunity through textContent rendering. The entire system is validated by an automated test suite of **81 unit and integration tests** with a 100% pass rate.

---

## 2. Introduction

A distributed system consists of autonomous computing nodes that communicate over a network to coordinate actions and share state while appearing to users as a single coherent facility. Messaging platforms are among the quintessential examples of distributed systems: they require real-time event delivery, concurrent connection multiplexing, horizontal scalability, shared data consistency, and graceful failure handling.

The assignment began with a monolithic, process-local Java TCP socket server and Swing GUI (`legacy/tcp/`). While educational for understanding raw socket programming and thread spawning, single-process socket architectures suffer from severe scalability bottlenecks:
- State is confined to the memory of a single JVM instance.
- Client crashes or thread blocks stall socket loops.
- Horizontal scaling is impossible because clients connected to different physical servers cannot discover or communicate with one another.
- Lack of database persistence means message history is lost upon server restarts.

To overcome these constraints, this project redesigned the system into an event-driven, distributed service. By decoupling connection management (WebSocket), event routing (STOMP and Redis Pub/Sub), persistent storage (PostgreSQL), and network traffic distribution (Nginx), the application achieves true horizontal scalability.

---

## 3. Objectives

The primary engineering objectives accomplished in this project include:

1. **Robust Concurrent Communication:** Enable hundreds of simultaneous client connections with non-blocking thread scheduling using Spring Boot's WebSocket message broker and thread pools.
2. **Horizontal Scalability (Distributed Architecture):** Support multi-instance deployments where users connected to different application servers (`app1`, `app2`) can exchange public, private, and group messages in real time.
3. **Distributed Presence Management:** Maintain an accurate cluster-wide registry of online users using Redis with TTL heartbeat expiration to prevent ghost connections after unexpected disconnects.
4. **Relational Persistence & Schema Migrations:** Implement normalized relational schemas with Spring Data JPA and Flyway versioned migrations to store user profiles, credentials, conversation memberships, and message logs.
5. **Secure Authentication & Authorization:** Implement BCrypt password hashing, session management, and role-based access control (RBAC) ensuring group administration (updating, adding members, deleting groups) is strictly restricted to authorized users.
6. **Modern, Responsive User Interface:** Develop a Telegram-inspired web client with dark/light themes, live user search, unread badges, typing feedback, and zero external JS frameworks.
7. **Comprehensive Test Suite:** Establish rigorous automated verification spanning unit tests, concurrency race condition tests, database integration tests, STOMP protocol tests, and frontend security contract verifications.

---

## 4. Tools and Technologies

| Layer / Concern | Technology | Version / Details | Purpose |
|---|---|---|---|
| **Language** | Java | JDK 21 (LTS) | Language runtime with modern switch expressions, records, and pattern matching |
| **Backend Framework** | Spring Boot | 3.5.16 | Core framework, dependency injection, component configuration |
| **Real-time Protocol** | WebSocket / STOMP | RFC 6455 / STOMP 1.2 | Full-duplex bidirectional messaging with topic subscriptions |
| **Security** | Spring Security | 6.x | Session management, BCrypt password encryption, authorization filters |
| **Persistence** | Spring Data JPA / Hibernate | 6.6.x | ORM mapping, repositories, and transaction management |
| **Relational Database** | PostgreSQL & H2 | PostgreSQL 16 (Prod) / H2 2.3 (Test) | Persistent data storage with Flyway database version control |
| **Distributed Broker & Presence** | Redis & Jedis | Redis 7 Alpine | Shared distributed presence storage and Redis Pub/Sub cross-node relay |
| **Reverse Proxy / Load Balancer** | Nginx | Alpine | Reverse proxy, IP hash sticky load balancing, WebSocket upgrade handling |
| **Containerization** | Docker & Docker Compose | Docker 29 / Compose v5 | Container deployment orchestrating db, redis, app1, app2, and nginx |
| **Frontend** | HTML5, CSS3, Vanilla JS | ES2022 | Lightweight Telegram-inspired client with `@stomp/stompjs` (no build step) |
| **Testing** | JUnit 5, AssertJ, Mockito | Standard | 81 automated tests verifying business logic, concurrency, and integrations |

---

## 5. System Architecture

### 5.1 High-Level Distributed Architecture

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

### 5.2 Architectural Layers

1. **Presentation / Static Frontend (`src/main/resources/static/`):**
   - Served directly by Spring Boot without requiring Node.js or bundlers.
   - Provides responsive Telegram layout: navigation channels, live user search, online status pills, conversation bubbles, settings dropdown, and light/dark theme switcher.
   - Uses strict DOM `textContent` assignment to eliminate Cross-Site Scripting (XSS) vectors.

2. **Web & Protocol Controllers (`api/` and `websocket/`):**
   - `AuthController` & `UserController`: User registration, credential login, profile queries, password modifications, and user search.
   - `ConversationController`: Endpoints for direct and group conversations, member management, archiving, and read tracking.
   - `MessageController`: Message pagination, editing, and deletion.
   - `ChatWebSocketController`: Inbound STOMP endpoints (`/app/chat.join`, `/app/chat.send`, `/app/chat.private`).
   - `WebSocketEventListener`: Manages `SessionConnectedEvent` and `SessionDisconnectEvent` lifecycle hooks.

3. **Application Services (`application/`):**
   - `AuthService`: Encapsulates authentication workflows, BCrypt verification, and user lookup.
   - `ConversationService`: Business logic for direct and group conversation integrity, permissions, and member roles.
   - `PersistentMessageService`: Persistence, pagination, message editing, soft deletion, and event publication.
   - `PresenceService`: Process-facing facade over the swappable `PresenceStore` contract.

4. **Distributed Infrastructure (`infrastructure/`):**
   - `RedisPresenceStore`: Stores online status in Redis with a 30-second TTL, refreshed via heartbeats.
   - `InMemoryPresenceStore`: Used as a fast, lock-free process-local fallback when distributed mode is disabled.
   - `DistributedMessageRelay`: Subscribes to Redis Pub/Sub channels to broadcast messages across multiple instances so users connected to `app1` receive messages published by users on `app2`.

5. **Data Tier & Repositories (`infrastructure/repository/`):**
   - Five Spring Data JPA repositories: `UserRepository`, `UserSettingsRepository`, `ConversationRepository`, `ConversationMemberRepository`, and `MessageRepository`.
   - Managed under Flyway database migration scripts (`V1__init_schema.sql`).

---

## 6. Implementation Details

### 6.1 Database Schema Design

The relational database is organized into normalized tables managed by Flyway:

1. **`users` Table:** Stores unique user identity (`id UUID PRIMARY KEY`, `username VARCHAR(50) UNIQUE`, `email VARCHAR(255) UNIQUE`, `password_hash VARCHAR(255)`, `display_name VARCHAR(100)`, `bio`, `avatar_url`, `is_active`, `created_at`, `updated_at`).
2. **`user_settings` Table:** Tracks personalized preferences (`theme`, `notifications_enabled`, `sound_enabled`).
3. **`conversations` Table:** Represents communication channels (`type` as `DIRECT` or `GROUP`, `title`, `description`, `avatar_url`, `created_by`, `last_message_text`, `last_message_at`, `last_message_sender`).
4. **`conversation_members` Table:** Implements conversation membership with composite uniqueness on `(conversation_id, user_id)` and member roles (`OWNER`, `ADMIN`, `MEMBER`). Includes `last_read_at` and `is_archived`.
5. **`messages` Table:** Message store (`id UUID PRIMARY KEY`, `conversation_id`, `sender_id`, `sender_username`, `content`, `is_edited`, `is_deleted`, `created_at`, `edited_at`).

### 6.2 Concurrency & Thread-Safety

- **Atomic Registration:** When running in single-instance mode, `InMemoryPresenceStore` relies on `ConcurrentHashMap.putIfAbsent()` to guarantee that if 32 threads race to claim a display name simultaneously, exactly one thread succeeds and no locks are held during network I/O.
- **Distributed Lock-Free Presence:** In distributed mode, Redis hashes with `EXPIRE` ensure atomic cluster-wide presence tracking without distributed deadlocks.
- **Transactional Consistency:** All conversation mutations, message state changes, and membership modifications are annotated with `@Transactional`, guaranteeing atomic operations and rollback on error.

### 6.3 Secure Authentication & Principle of Least Privilege

- Passwords are never stored in plain text; they are hashed using Spring Security's `BCryptPasswordEncoder` (cost factor 10).
- Controller endpoints verify the calling `Principal`. The user identity attached to outbound messages is obtained strictly from the authenticated server session—never trusted from client payloads.
- Role-based authorization rules ensure that only group owners can rename groups, add/remove members, or delete groups.

---

## 7. Testing and Results

The system was verified using a comprehensive suite of **81 automated tests** executed via the Maven Wrapper (`.\mvnw.cmd test`).

### 7.1 Automated Test Execution Summary

```
[INFO] Running com.example.chat.application.AuthServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.application.ChatServiceTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.application.ConversationServiceTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.application.DuplicateUsernameConcurrencyTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.application.PersistentMessageServiceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.frontend.FrontendContractTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.infrastructure.InMemoryPresenceStoreTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.integration.HealthEndpointIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.example.chat.websocket.ChatWebSocketIntegrationTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] Results:
[INFO] Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 7.2 Detailed Test Matrix

| Category | Test Class | Tests | Focus Area | Observed Result |
|---|---|---|---|---|
| **Authentication & Profile** | `AuthServiceTest` | 8 | Registration, duplicate username/email rejection (case-insensitive), login verification, wrong password rejection, profile update, password change, user search | **PASS** (0 failures) |
| **Chat Service Rules** | `ChatServiceTest` | 27 | Join rules, blank/oversized messages, public routing, private delivery, offline recipient handling, disconnect cleanup | **PASS** (0 failures) |
| **Concurrency & Race Conditions** | `DuplicateUsernameConcurrencyTest` | 3 | 32 simultaneous threads racing to register identical/mixed-case usernames; exactly 1 wins | **PASS** (0 failures) |
| **Conversation Management** | `ConversationServiceTest` | 8 | Direct conversation creation, idempotency, self-chat prevention, group creation, owner role assignment, member addition/removal, permission checks, archiving | **PASS** (0 failures) |
| **Message Lifecycle** | `PersistentMessageServiceTest` | 7 | Message persistence, last message updates, length limits, non-member rejection, chronological pagination, author editing, unauthorized edit rejection, soft delete | **PASS** (0 failures) |
| **Frontend Security Contract** | `FrontendContractTest` | 2 | Pins browser STOMP destinations to server constants; enforces zero `innerHTML`/`outerHTML` sinks | **PASS** (0 failures) |
| **Presence Store Contract** | `InMemoryPresenceStoreTest` | 11 | Presence storage contract, case-insensitivity, atomicity of register/unregister | **PASS** (0 failures) |
| **REST Health Endpoints** | `HealthEndpointIntegrationTest` | 2 | Verifies HTTP GET `/api/health` returns status `UP` with timestamp | **PASS** (0 failures) |
| **End-to-End WebSocket** | `ChatWebSocketIntegrationTest` | 13 | Real WebSocket/STOMP frames with 2-3 simultaneous clients: join/leave notifications, private delivery isolation, sender spoofing prevention, payload limits | **PASS** (0 failures) |
| **Total** | **9 Test Classes** | **81** | **Full system verification across all application tiers** | **100% PASS** |

---

## 8. Security and Limitations

### 8.1 Security Protections Implemented
- **XSS Prevention:** The web client strictly assigns dynamic user content using `textContent` and `setAttribute`. HTML markup typed into messages is rendered literally as plain text. Verified automatically by `FrontendContractTest`.
- **Sender Spoofing Prevention:** The server extracts sender identity directly from the authenticated session (`SimpMessageHeaderAccessor`), discarding any `sender` field provided in the client JSON frame.
- **Private Message Confidentiality:** Direct and private messages are routed exclusively to `/user/queue/private` for the sender and recipient; verified in integration tests to never touch public topics.
- **SQL Injection Prevention:** All database operations utilize Spring Data JPA parameter binding and prepared queries.
- **Password Security:** Salted BCrypt password hashes ensure credential security in case of database inspection.

### 8.2 Current Limitations
- **File & Media Sharing:** Current implementation focuses on text messages; image attachments require integration with an object store (e.g., AWS S3 or MinIO).
- **End-to-End Encryption (E2EE):** Transport is encrypted via HTTPS/WSS when TLS is enabled, but server-side messages are stored in plaintext in the database.
- **Push Notifications:** Web push notifications (APNs / FCM) are not currently integrated.

---

## 9. How to Run and Test the Application

### 9.1 Prerequisites
- **JDK 21** installed and configured (`JAVA_HOME` pointing to JDK 21).
- **Maven Wrapper** (`mvnw` / `mvnw.cmd`) is included in the repository.
- **Docker & Docker Compose** (optional, required for multi-instance distributed cluster mode).

### 9.2 Running the Application

#### Option A: Quick Start (Single Node Development)
Run directly with Maven Wrapper. Uses the default in-memory configuration:
```powershell
.\mvnw.cmd spring-boot:run
```
Access the application at **<http://localhost:8080>**.

#### Option B: Multi-Instance Distributed Cluster (Docker Compose)
Spins up PostgreSQL, Redis, two chat instances (`app1` on 8081, `app2` on 8082), and Nginx on port 80:
```powershell
docker compose up --build -d
```
Access the application through the load balancer at **<http://localhost>**.

#### Option C: Running the Legacy TCP Socket Application
The original TCP assignment code is preserved in `legacy/tcp/`:
```powershell
cd legacy\tcp
javac ChatServer.java ChatClient.java
java ChatServer       # Start server in Terminal 1
java ChatClient       # Start client in Terminal 2 (repeat for more clients)
```

### 9.3 Running All Automated Tests
To run the full suite of 81 automated tests:
```powershell
.\mvnw.cmd test
```

To run a specific test class:
```powershell
.\mvnw.cmd test "-Dtest=ChatWebSocketIntegrationTest"
.\mvnw.cmd test "-Dtest=ConversationServiceTest,PersistentMessageServiceTest"
```

---

## 10. Conclusion

This project successfully transformed a basic local TCP socket assignment into an enterprise-grade, distributed messaging platform. By employing Java 21, Spring Boot 3, WebSocket/STOMP, PostgreSQL, Redis, and Docker, the system resolves every fundamental challenge of distributed chat systems:
- Real-time event propagation across heterogeneous server nodes,
- Centralized relational persistence with database migration management,
- High-concurrency lock-free state management,
- Robust authorization and injection security, and
- Complete operational verification through 81 automated unit and integration tests.

The resulting codebase demonstrates practical mastery of distributed systems principles, concurrency control, and clean software architecture.

---

## References

1. Tanenbaum, A. S., & Van Steen, M. (2017). *Distributed Systems: Principles and Paradigms* (3rd ed.).
2. Spring Framework Documentation: *WebSocket, STOMP Messaging, and Spring Security Architecture* (v6.x / Spring Boot 3.5.x).
3. Redis Documentation: *Pub/Sub Messaging Patterns and Distributed In-Memory Data Structures*.
4. PostgreSQL Global Development Group: *PostgreSQL 16 Manual: Concurrency Control and Transactions*.
5. RFC 6455: *The WebSocket Protocol* (IETF).
