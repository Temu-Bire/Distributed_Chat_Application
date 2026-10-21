# Distributed Chat Application (Java 21 + Spring Boot 3)

A high-performance, Telegram-inspired distributed messaging platform built with **Java 21**, **Spring Boot 3**, **WebSocket / STOMP**, **Spring Data JPA**, **PostgreSQL**, **Redis**, and a clean **HTML5/CSS3/Vanilla JS** frontend.

The application evolved from an initial single-process Java TCP socket and Swing assignment into an enterprise-grade distributed system supporting user accounts, direct and group conversations, message CRUD, cluster-wide presence, and cross-instance messaging across load-balanced nodes.

---

## Table of Contents

1. [Features](#features)
2. [Architecture Overview](#architecture-overview)
3. [Prerequisites](#prerequisites)
4. [How to Run the Application](#how-to-run-the-application)
   - [Mode 1: Quick Start (Single Node Development)](#mode-1-quick-start-single-node-development)
   - [Mode 2: Local Development with PostgreSQL and Redis](#mode-2-local-development-with-postgresql-and-redis)
5. [How to Test the Application Fully](#how-to-test-the-application-fully)
   - [Automated Test Suite](#automated-test-suite)
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

### 🎨 ChatSphere Modern Frontend
- **Design System:** Dark navy sidebar rail, white content panels, blue accents, crisp typography, and fluid CSS transitions.
- **Light / Dark Theme:** One-click theme toggle with immediate UI recoloring.
- **Responsive Layout:** Side-by-side desktop view and mobile bottom-tab navigation.
- **Message Bubbles:** Incoming (left, gray) vs. outgoing (right, blue, double checkmarks `✓✓`).
- **Real-Time Elements:** Live user search, online indicators, typing character counter, emoji picker, and toast alerts.
- **Loading & Empty States:** Conversation list and message thread distinguish "still loading" from "genuinely empty"; submit buttons show busy feedback.
- **Landing Page:** `/` serves a responsive marketing page whose Log in / Get started buttons route into the real client at `/app`.
- **Security-First:** 100% safe DOM rendering using `textContent` (zero `innerHTML` sinks, verified by automated tests), strict CSP with no `unsafe-*`, HttpOnly/SameSite session cookies with rotation on login, and 429 rate limits on login, registration, friend requests, and message sends.

### 🔐 User Accounts & Authentication
- **User Registration:** Display name, unique username, unique email, and password.
- **Secure Password Hashing:** BCrypt password encryption (cost factor 10).
- **Session-Based Authentication:** Standard Spring Security session management with cookie handling.
- **Profile Management:** Update bio, display name, avatar URL, or change password with old-password verification.
- **User Search & Discovery:** Search active registered users by username or display name with pagination and live friendship status.

### 👥 Friends & Social Graph
- **Friend Requests:** Send, accept, reject, and cancel requests; incoming/outgoing lists persisted in the database.
- **Friends Directory:** Friends, request counts, and message shortcuts all load from `/api/friends` and `/api/users/search` — never hardcoded.
- **Friendship Status in Search:** Every search result carries its `NONE / PENDING_OUTGOING / PENDING_INGOING / FRIENDS` state so the UI can show the right action.

### 💬 Persistent Conversations & Group Chats
- **Direct 1-on-1 Chats:** Start private chats with any registered user. Idempotent creation prevents duplicates and automatically unarchives inactive chats.
- **Multi-Member Group Chats:** Create groups with custom titles, descriptions, and avatars.
- **Group Discovery & Join Requests:** Browse public groups, request to join, and let owners accept or reject.
- **Group Invitations:** Members invite friends to groups; invitees accept, reject, or see cancellations.
- **Role-Based Group Administration:** Group creator receives `OWNER` role; owners can update group details, add members, or remove members.
- **Conversation Controls:** Archive / unarchive conversations and mark conversations as read.

### 📝 Persistent Message Lifecycle & History
- **Relational Storage:** Messages stored in PostgreSQL (or embedded H2) via Spring Data JPA with Flyway schema migration.
- **Chronological Pagination:** Retrieve conversation history in ordered pages.
- **Message Editing:** Authors can edit their own sent messages with real-time `edited` indicator.
- **Soft Deletion:** Authors or group owners can delete messages; content is replaced with `"This message was deleted."` while preserving thread continuity.
- **STOMP Broadcasts:** Dynamic conversation events (`NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED`) pushed to subscribers on `/topic/conversations/{id}`.

### 🌐 Distributed Multi-Instance Architecture
- **Distributed Presence:** `RedisPresenceStore` tracks online users across multiple application nodes with TTL-based heartbeats to prevent ghost sessions.
- **Cross-Instance Message Relay:** `DistributedMessageRelay` uses Redis Pub/Sub to forward messages across cluster instances.
- **Load Balancing:** Any HTTP/WebSocket-aware load balancer (nginx, ALB, …) can front N instances; session stickiness is recommended.
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
                          |   Load Balancer        |
                          |  (nginx / ALB, opt.)   |
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

Set `JAVA_HOME` in PowerShell if not already configured:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" # or your JDK 21 path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -v
```

---

## How to Run the Application

### Mode 1: Quick Start (Single Node Development)

Run the application immediately with zero external dependencies. Uses an embedded file-backed H2 database (PostgreSQL mode) and a lock-free in-memory presence store:

```powershell
.\mvnw.cmd spring-boot:run
```

Once you see `Started ChatApplication`, open **<http://localhost:8081>** — the ChatSphere landing page. The chat client lives at **<http://localhost:8081/app>** (login/register are also reachable from the landing page buttons).

> **Port Conflict:** If port 8081 is occupied, run on another port:
> ```powershell
> .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8090"
> ```
> No other change is needed: the default WebSocket/CORS origin patterns cover any `localhost` port.

---

### Mode 2: Local Development with PostgreSQL and Redis

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

## How to Test the Application Fully

### Automated Test Suite

Run all automated tests with Maven:

```powershell
.\mvnw.cmd test
```

### Test Suite Breakdown:

| Test Class | Tests | What is Tested |
|---|---|---|
| **`AuthServiceTest`** | 8 | User registration, BCrypt password hashing, duplicate username/email rejection, login, profile updates, password change, user search |
| **`ChatServiceTest`** | 14 | STOMP join rules: registration, whitespace trimming, blank/long/control-character names, duplicate names, rejoin; leave: presence broadcast, name reuse, idempotency |
| **`ConcurrencyRaceTest`** | 6 | Latched thread pools racing each check-then-write path: idempotent duplicate sends, competing group joins, join-request vs admin add, simultaneous DM creation both directions, reaction toggle races, double-accept of a join request |
| **`ConversationOwnershipTransferTest`** | 2 | Group ownership transfer rules |
| **`ConversationServiceTest`** | 8 | Direct conversation creation, idempotency, self-chat prevention, group creation with owner role, member additions and removals, RBAC permissions, voluntary leave, archiving |
| **`DistributedConcurrencyTest`** | 1 | Race-condition testing across presence store operations |
| **`FriendServiceTest`** | 9 | Friend request send/accept/reject/cancel, paired friendship rows, removal, status lookups, request messages |
| **`GroupModerationTest`** | 9 | Privacy settings, moderator role, member blocking/unblocking, join requests and invitations |
| **`PersistentMessageServiceTest`** | 13 | Message persistence, conversation summaries, size validation, non-member rejection, replies, emoji reactions, idempotent sends, chronological pagination, author edit verification, unauthorized edit rejection, soft-delete filtering |
| **`FrontendContractTest`** | 3 | `app.js` pins exactly the server's STOMP destinations, zero `innerHTML` sinks, page stays zoomable and shows loading vs empty states |
| **`LandingPageIntegrationTest`** | 4 | `/` serves the landing page, `/app` serves the client, every button resolves to a real route, marketing security claims match enforcement |
| **`InMemoryPresenceStoreTest`** | 11 | Presence storage contract, case-insensitivity, thread-safe register/unregister atomicity |
| **`FriendControllerIntegrationTest`** | 3 | HTTP friend-request flow end to end |
| **`FullE2EChatJourneyTest`** | 1 | Register → friend → direct chat → message journey over REST |
| **`HealthEndpointIntegrationTest`** | 2 | HTTP GET `/api/health` verifying health status reporting |
| **`RateLimiterTest`** | 7 | Fixed-window budgets, rollover, `Retry-After`, key isolation, disabled-mode, 16-thread shared-budget atomicity |
| **`RateLimitIntegrationTest`** | 3 | Real HTTP: 429 + `RATE_LIMITED` + `Retry-After` on exhausted login/friend-request/message budgets; reads unaffected |
| **`SessionHardeningIntegrationTest`** | 3 | Cookie HttpOnly/SameSite=Lax, session id rotation on login (fixation defence), CSP / X-Frame-Options / nosniff headers |
| **`ChatWebSocketIntegrationTest`** | 4 | Real WebSocket/STOMP frames with 2–3 simultaneous clients: presence fan-out, duplicate-name refusal, oversized input, disconnect |
| **`WebSocketSecurityIntegrationTest`** | 3 | Anonymous and non-member subscription refusals; delivery only to authorized recipients |
| **Total** | **114** | **100% Passing (0 failures, 0 errors, 0 skipped)** |

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
   - Navigate to `http://localhost:8081/app` and register as `alice` (display name, username, email, password).
   - Verify: the ChatSphere shell loads with the dark sidebar rail and the welcome panel.
3. **Open Window 2 (Bob in Incognito/Second Browser):**
   - Navigate to `http://localhost:8081/app` and register as `bob`.
4. **Friends:**
   - As Alice, open **Friends**, search for `bob`, and click **Add Friend**.
   - As Bob, open **Friend Requests**, see the incoming request, and click **Accept**.
   - Verify: Bob appears in Alice's friends list (and vice versa), persisted in the `friendships` table.
5. **Direct Messaging:**
   - Alice clicks Bob in the friends list, types a message, and presses Enter.
   - Verify: the message appears in both windows in real time via `/topic/conversations/{id}`; outgoing bubbles show `✓✓`.
6. **Groups:**
   - Alice creates a group from **My Groups**, invites Bob, and Bob accepts the invitation.
   - Verify: group messages fan out to both members; the member list shows roles.
7. **Theme Switching:**
   - Click the theme toggle in Settings. The interface transitions between light and dark modes.

---

### Testing Distributed Multi-Instance Messaging

To verify that two users connected to **two different application instances** can communicate across the cluster:

1. **Start PostgreSQL and Redis locally** (see Mode 2 above).
2. **Start two app instances:**
   ```powershell
   # Terminal 1
   $env:CHAT_DISTRIBUTED_ENABLED = "true"; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
   # Terminal 2
   $env:CHAT_DISTRIBUTED_ENABLED = "true"; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
   ```
3. **Open Alice on Instance 1** (`http://localhost:8081`) and **Bob on Instance 2** (`http://localhost:8082`).
4. **Observe Distributed Synchronization:**
   - Both users see each other as online (Redis-backed presence).
   - Messages sent on one instance are relayed to the other through Redis Pub/Sub.

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

#### Users (`/api/users`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/users/search?query=...` | Search users by name (excludes self, includes friendship status) | Authenticated |
| `GET` | `/api/users/profile` | Current profile | Authenticated |
| `PUT` | `/api/users/profile` | Update profile bio and display name | Authenticated |
| `PUT` | `/api/users/password` | Change user password | Authenticated |
| `DELETE` | `/api/users/account` | Deactivate account | Authenticated |

#### Friends (`/api/friends`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/friends` | List friends (with online flag) | Authenticated |
| `POST` | `/api/friends/requests` | Send a friend request | Authenticated |
| `GET` | `/api/friends/requests/incoming` | Incoming pending requests | Authenticated |
| `GET` | `/api/friends/requests/outgoing` | Outgoing pending requests | Authenticated |
| `POST` | `/api/friends/requests/{id}/accept` | Accept request (creates paired friendship rows) | Authenticated |
| `POST` | `/api/friends/requests/{id}/reject` | Reject request | Authenticated |
| `DELETE` | `/api/friends/requests/{id}` | Cancel own outgoing request | Authenticated |
| `DELETE` | `/api/friends/{friendId}` | Remove a friend | Authenticated |
| `GET` | `/api/friends/status/{targetUserId}` | Friendship status for one user | Authenticated |

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
| `GET` | `/api/conversations/groups/discover` | Discover joinable groups | Authenticated |
| `POST` | `/api/conversations/groups/{id}/join-requests` | Request to join a group | Authenticated |
| `GET/POST` | `/api/conversations/groups/{id}/join-requests[/{reqId}/accept|reject]` | Owner review of join requests | Owner |
| `GET` | `/api/conversations/join-requests` | My outgoing join requests | Authenticated |
| `POST` | `/api/conversations/groups/{id}/invitations` | Invite users to a group | Member |
| `GET` | `/api/conversations/invitations` | My group invitations | Authenticated |
| `POST` | `/api/conversations/invitations/{id}/accept|reject` | Respond to an invitation | Invitee |

#### Messages (`/api/conversations/{id}/messages`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/conversations/{id}/messages` | Paginate messages (`page`, `size`) | Member |
| `POST` | `/api/conversations/{id}/messages` | Send message; accepts `replyToId` and idempotent `clientMessageId`; rate limited per sender | Member |
| `PUT` | `/api/messages/{msgId}` | Edit existing message | Author only |
| `DELETE` | `/api/messages/{msgId}` | Soft delete message | Author / Owner |
| `POST` | `/api/messages/{msgId}/reactions` | Toggle an emoji reaction | Member |

#### Health (`/api/health`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/health` | Health status and uptime | Public |

---

### WebSocket / STOMP Destinations

| Direction | Destination | Purpose | Payload |
|---|---|---|---|
| **Client → Server** | `/app/chat.join` | Join chat and register presence | `{"username": "alice"}` |
| | `/app/conversations/{id}/send` | Send a message into a persisted conversation | `{"content": "Hello!", "replyToId": "<uuid|null>", "clientMessageId": "<uuid>"}` — `clientMessageId` makes retries idempotent; the server assigns sender and timestamps |
| **Server → Client** | `/topic/users` | Presence changes & online list | User list & join/leave event |
| | `/topic/conversations/{id}` | Persistent conversation events | `NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_REACTED` — published only after the DB commit; subscriptions are refused (403) for non-members |
| | `/user/queue/errors` | Error notifications | `{"code": "...", "message": "..."}` |

---

## Environment & Configuration Reference

Configure via environment variables or in `src/main/resources/application.yml`:

| Environment Variable | Default Value | Description |
|---|---|---|
| `PORT` | `8081` | HTTP and WebSocket server port |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/chatsphere` (PostgreSQL mode) | JDBC connection URL; set to `jdbc:postgresql://...` for a managed database |
| `SPRING_DATASOURCE_USERNAME` | `sa` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | *(empty)* | Database password |
| `SPRING_FLYWAY_ENABLED` | `true` | Flyway is the sole schema owner (`ddl-auto: validate` — entity/migration drift fails startup) |
| `SPRING_REDIS_HOST` | `localhost` | Redis server hostname |
| `SPRING_REDIS_PORT` | `6379` | Redis server port |
| `CHAT_DISTRIBUTED_ENABLED` | `false` | Enable Redis cluster presence & Pub/Sub relay |
| `CHAT_RATE_LIMIT_ENABLED` | `true` | Master switch for the fixed-window rate limiter |
| `RATE_LIMIT_LOGIN` | `10` | Login attempts per minute per IP |
| `RATE_LIMIT_REGISTER` | `5` | Registrations per minute per IP |
| `RATE_LIMIT_FRIEND_REQUESTS` | `20` | Friend requests per minute per user |
| `RATE_LIMIT_MESSAGES` | `60` | Message sends per minute per sender (covers REST **and** STOMP) |
| `SESSION_TIMEOUT` | `8h` | Server-side session lifetime |
| `COOKIE_SECURE` | `false` | Set `true` behind TLS so the session cookie is `Secure` |
| `UPLOAD_DIR` | `./uploads` | Where avatar uploads are written (served under `/uploads/**`) |

---

## Project Structure

```
.
├── pom.xml                             # Spring Boot 3.x, Java 21, JPA, Security, Redis
├── README.md                           # This documentation
├── docs/
│   └── architecture.md                 # Architecture diagrams, decisions, and layers
└── src/
    ├── main/
    │   ├── java/com/example/chat/
    │   │   ├── ChatApplication.java    # Application entry point
    │   │   ├── api/                    # REST controllers (Auth, Users, Friends, Convs, Messages, …)
    │   │   ├── application/            # Business services (Auth, Friend, Conv, Message, Presence)
    │   │   ├── config/                 # WebSocket, CORS/resource, property configuration
    │   │   ├── domain/                 # Domain entities, enums, and value objects
    │   │   ├── dto/                    # Request/Response data transfer objects
    │   │   ├── exception/              # Global error handling and ChatException
    │   │   ├── infrastructure/         # Repositories, InMemory and Redis Presence Stores
    │   │   ├── security/               # SecurityConfig (sessions, CSP), RateLimiter + filter
    │   │   └── websocket/              # STOMP controllers and connection event listeners
    │   └── resources/
    │       ├── application.yml         # Application configuration
    │       ├── db/migration/           # Flyway SQL schema migrations V1–V4 (sole schema owner)
    │       └── static/                 # ChatSphere vanilla web frontend
    │           ├── landing.html        # Marketing landing page served at /
    │           ├── index.html          # Chat layout and modals (served at /app)
    │           ├── css/                # styles.css (app) and landing.css (landing page)
    │           └── js/app.js           # Frontend controller (100% XSS safe)
    └── test/java/com/example/chat/
        ├── application/                # Service & concurrency race tests
        ├── frontend/                   # Contract, XSS-safety & landing page tests
        ├── infrastructure/             # Presence store tests
        ├── integration/                # REST integration tests
        ├── security/                   # Rate limiter & session hardening tests
        └── websocket/                  # Full-duplex WebSocket STOMP + authorization tests
```
