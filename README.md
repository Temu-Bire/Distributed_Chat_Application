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
   - [Seeding demo data (opt-in, development only)](#seeding-demo-data-opt-in-development-only)
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
- **Light / Dark Theme:** One-click theme toggle, persisted to the account through `/api/settings`.
- **Responsive Layout:** Side-by-side desktop view and mobile bottom-tab navigation.
- **Message Bubbles:** Incoming (left, gray) vs. outgoing (right, blue), with per-message read ticks that advance live when the other side reads.
- **Real-Time Elements:** Live user search, online indicators, typing dots from the peer, emoji picker, reply previews, reaction pills, and toast alerts.
- **Pinned Messages:** A pin bar above the thread lists pinned messages for the conversation and jumps to them.
- **In-Conversation Search:** A search panel over `GET /api/conversations/{id}/messages/search`, plus "load older" pagination instead of a fixed history window.
- **File & Image Sharing:** Attachments upload to `/api/uploads/media` and render as an image preview or a named download chip inside the bubble.
- **Loading & Empty States:** Conversation list and message thread distinguish "still loading" from "genuinely empty"; submit buttons show busy feedback.
- **Confirmation Dialogs:** Every destructive action (delete message, remove friend, remove or block member, revoke invite link, leave or delete group, remove avatar, log out) goes through an accessible in-app dialog (`role="dialog"`, `aria-modal`, focus trap, Escape = cancel) instead of `window.confirm`.
- **Resilient Connection:** STOMP 1.1 heartbeats with an inbound-silence watchdog, capped exponential reconnect (1 s → 15 s), REST catch-up of everything missed while offline, and a REST fallback so the composer still sends while the socket is down.
- **Landing Page:** `/` serves a responsive marketing page whose Log in / Get started buttons route into the real client at `/app`.
- **Security-First:** 100% safe DOM rendering using `textContent` (zero `innerHTML` sinks, verified by automated tests), strict CSP with no `unsafe-*`, HttpOnly/SameSite session cookies with rotation on login, and 429 rate limits on login, registration, friend requests, and message sends.

### 🔐 User Accounts & Authentication
- **User Registration:** Display name, unique username, unique email, and password.
- **Secure Password Hashing:** BCrypt password encryption (cost factor 10).
- **Session-Based Authentication:** Standard Spring Security session management with cookie handling.
- **Profile Management:** Update bio and display name, change the avatar through the file-upload endpoint from the profile page, or change the password with old-password verification.
- **User Search & Discovery:** Search active registered users by username or display name with pagination and live friendship status.

### 👥 Friends & Social Graph
- **Friend Requests:** Send, accept, reject, and cancel requests; incoming/outgoing lists persisted in the database.
- **Friends Directory:** Friends, request counts, and message shortcuts all load from `/api/friends` and `/api/users/search` — never hardcoded.
- **Friendship Status in Search:** Every search result carries its `NONE / PENDING_OUTGOING / PENDING_INGOING / FRIENDS` state so the UI can show the right action.

### 💬 Persistent Conversations & Group Chats
- **Direct 1-on-1 Chats:** Start private chats with any registered user. Idempotent creation prevents duplicates and automatically unarchives inactive chats.
- **Multi-Member Group Chats:** Create groups with custom titles, descriptions, and avatars.
- **Public / Private Groups:** Privacy is stored per group; only public groups appear in discovery, while private groups are reachable by invitation or invite link.
- **Group Discovery & Join Requests:** Browse public groups, request to join, and let owners or moderators accept, reject, or withdraw the request.
- **Group Invitations:** Owners and moderators invite friends to groups; invitees accept, reject, or see cancellations.
- **Invitation Links:** Owners and moderators create revoke-able links (128-bit random token). The token travels only in a POST body, preview shows the group before joining, and joining re-checks blocking, existing membership, and the link's own expiry/use limit — including for private groups.
- **Role-Based Group Administration:** `OWNER` / `MODERATOR` / `MEMBER`. Moderators and owners edit the group, add or remove members, review join requests, invite, block and pin; only the owner changes roles, and ownership moves by the owner leaving.
- **Member Blocking:** Blocked users cannot join or be re-added; unblocking is an explicit moderator action.
- **Conversation Controls:** Archive / unarchive conversations, leave a group, delete a group (owner), and mark a conversation read.
- **Unread Counts:** Each conversation summary carries a server-computed `unreadCount` from the member's last-read position.

### 📝 Persistent Message Lifecycle & History
- **Relational Storage:** Messages stored in PostgreSQL (or embedded H2) via Spring Data JPA with Flyway schema migration.
- **Chronological Pagination:** Retrieve conversation history in ordered pages.
- **Replies:** Threaded replies with a server-supplied preview of the quoted message.
- **Emoji Reactions:** Toggle per user per emoji, aggregated in every read and broadcast.
- **Message Editing:** Authors can edit their own sent messages with real-time `edited` indicator.
- **Soft Deletion:** Authors or group owners can delete messages; content is replaced with `"This message was deleted."` while preserving thread continuity.
- **Pinning:** Members with permission pin and unpin messages; `GET /api/conversations/{id}/pinned` returns the current pins.
- **Search:** Case-insensitive substring search restricted to conversations the caller belongs to.
- **Forwarding:** Server-side copy into another conversation, gated by membership in **both** source and destination so a forward can't leak from a conversation the sender left.
- **Attachments:** Images and PDFs uploaded through `POST /api/uploads/media`, type-sniffed from magic bytes, size-capped, stored outside the web root with generated names, and referenced from the message row.
- **Typing Indicators:** `POST` to `/app/conversations/{id}/typing` throttles to one frame per 2 s per member and fans out an ephemeral `TYPING` event.
- **Read Receipts:** `PUT /api/conversations/{id}/read` records the reader's position, broadcasts `READ_UPDATE`, and reports `readByCount` per message.
- **STOMP Broadcasts:** Conversation events (`NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_REACTED`, `MESSAGE_PINNED`, `READ_UPDATE`, `TYPING`) pushed to subscribers on `/topic/conversations/{id}`, published only after the database commit.

### 🔔 Real-Time Notifications
- **Per-User Private Topic:** `/topic/notifications/{userId}`, enforced by the STOMP subscription guard so only that user's own session can subscribe.
- **Covered Events:** New messages, `@username` mentions, direct replies, friend requests and acceptances, group invitations, join requests, and join acceptances.
- **Preference Gating:** Four switches per user (`notifyMessages`, `notifyMentions`, `notifyFriendRequests`, `notifyGroupActivity`) under a master `notificationsEnabled` switch, read from the database at publish time.
- **Not Duplicated:** Notifications are deliberately not stored — they point at state that is already persisted, so an offline client recovers the truth (unread counts, pending requests) on its next load instead of a second copy that could drift.

### 🔒 Security & Access Control
- **Rate Limiting:** Fixed-window budgets return 429 + `Retry-After` — login and register per IP, friend requests per user, message sends per sender at one choke point that covers REST **and** STOMP. A retry of an already-persisted send never consumes budget.
- **Secure Uploads:** Type sniffed from magic bytes against an allow-list (PNG, JPEG, GIF, WebP, PDF), size-capped, stored under a generated name outside the source tree, and served with the type the server chose rather than the one the browser claimed.
- **Input Validation:** Bean Validation on every request DTO **and** the same rules re-checked inside the services, so a future adapter that skips validation cannot bypass them.
- **Access Control:** Identity always resolves from the session principal, never the request body; every service re-checks membership and role before reading or writing, which closes the IDOR and privilege-escalation paths.
- **Session Protection:** Server-side Spring Security session, `changeSessionId()` on login, HttpOnly + SameSite=Lax cookie, `Secure` behind TLS, strict CSP with no `unsafe-*`.
- **Authorized Subscriptions Only:** A STOMP channel interceptor refuses `/topic/conversations/{id}` for anonymous or non-member sessions and restricts `/topic/notifications/{userId}` to that user's own session — the UUID in the path is a convenience, not the authorization.
- **Capability Tokens in Bodies:** Invite-link tokens are 128-bit random values that travel only in POST bodies, never in a URL that a proxy or access log would retain.

### 🌐 Distributed Multi-Instance Architecture
- **Distributed Presence:** `RedisPresenceStore` tracks online users across multiple application nodes in two Redis hashes, with name claims made atomic by `HSETNX`. Entries are removed on disconnect — including disconnects the STOMP heartbeat watchdog notices — but they do **not** carry a TTL, so an instance that dies without cleaning up leaves ghost entries until it is restarted (see Known limitations in [docs/architecture.md](docs/architecture.md)).
- **Cross-Instance Event Relay:** `DistributedMessageRelay` publishes every conversation, presence, typing, and notification frame to the Redis Pub/Sub channel `chat:cluster:events`; each node re-broadcasts it into its own in-process broker and drops frames it originated itself.
- **Complete-Snapshot payloads:** Presence frames carry the whole online list taken from the shared store, so a replay on another node is a correct snapshot rather than a partial delta.
- **Load Balancing:** Any HTTP/WebSocket-aware load balancer (nginx, ALB, …) can front N instances; session stickiness is recommended.
- **Single-Node Fallback:** Without Redis, `InMemoryPresenceStore` is used and the relay bean is absent — services look it up through `ObjectProvider` at publish time, so the same code paths run either way.
- **Verified By Contract, Not By Live Cluster:** relay behaviour is covered by `DistributedMessageRelayTest` and `CrossInstanceFanoutTest` (which asserts each event reaches the relay with the right destination). Running two instances against a real Redis has not been exercised in this environment.

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

### Seeding demo data (opt-in, development only)

The app ships **empty**. Nothing is inserted unless you ask for it: the seeder bean
only exists in the context when `chat.seed.enabled=true`, which is not set in
`application.yml`, in `.env.example`, or by any test except its own.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--chat.seed.enabled=true"
```

One startup inserts a small connected world and then never runs again on that
database:

| | |
| --- | --- |
| **Accounts** | 10 — `mira`, `danny`, `lena`, `tomas`, `sara`, `owen`, `ravi`, `yuki`, `nadia`, `phil` |
| **Login** | `<username>@seed.local`, shared password `seedpass123` (`DataSeeder.PASSWORD`) |
| **Social graph** | 7 accepted friendships, 2 still-pending requests so the inbox is not empty |
| **Groups** | `Design Standup` (public, one moderator, a pending join request, a live invite link), `Weekend Climbing` (private, a pending invitation), `Build Failures` (public, one blocked member) |
| **Direct chats** | 4 |
| **Messages** | replies, 👍/🎉/📌/👀 reactions, a pin, an edit, a soft delete, a cross-conversation forward, and partial read state so unread badges differ per person |
| **Preferences** | `yuki` has notifications off, `lena` runs the dark theme, `ravi` mutes mentions |

Every row arrives through the real services — BCrypt hashing, paired friendship
rows, membership roles, after-commit broadcasts, the per-sender rate budget — not
raw SQL, so seeded data behaves exactly like browser-created data. The whole world
is **one transaction**: if any step fails, nothing is left behind, which matters
because the "already seeded" guard looks for a single account and would otherwise
mistake a half-written database for a completed one. Re-running the flag against a
seeded database logs `Seed skipped` and changes nothing.

> **Do not enable this in production.** These are development fixtures on a
> throwaway database with one shared password and a `.seed.local` domain that does
> not resolve. Turn the flag off for any deployment that has real users.

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
| **`ConcurrencyRaceTest`** | 7 | Latched thread pools racing each check-then-write path: idempotent duplicate sends, competing group joins, join-request vs admin add, simultaneous DM creation both directions, reaction toggle races, double-accept of a join request |
| **`ConversationOwnershipTransferTest`** | 2 | Group ownership transfer rules |
| **`ConversationServiceTest`** | 8 | Direct conversation creation, idempotency, self-chat prevention, group creation with owner role, member additions and removals, RBAC permissions, voluntary leave, archiving |
| **`CrossInstanceFanoutTest`** | 10 | Every cluster-visible event reaches `DistributedMessageRelay` with the correct destination and payload: new message, edit, delete, reaction, pin, read receipt, typing, forward, notification, presence join — with a mocked relay so no Redis is needed |
| **`DistributedConcurrencyTest`** | 1 | Race-condition testing across presence store operations |
| **`FriendServiceTest`** | 9 | Friend request send/accept/reject/cancel, paired friendship rows, removal, status lookups, request messages |
| **`GroupInviteLinkTest`** | 20 | Token entropy/format, expiry and use limits, revoke, blocking, duplicate membership, uniform refusal of every unusable state, private-group join via link, admin-only creation |
| **`GroupModerationTest`** | 9 | Privacy settings, moderator role, member blocking/unblocking, join requests and invitations |
| **`MessageAttachmentTest`** | 4 | Attachment persistence on the message row, magic-byte type sniffing, size rejection, non-member refusal |
| **`MessageForwardingTest`** | 5 | Forward requires membership in source **and** destination, deleted messages cannot be forwarded, self-forward rejected, forwarded flag set, idempotent retry |
| **`MessageSearchTest`** | 6 | Case-insensitive substring search, member-only access, limit, blank query, control characters, deleted-message exclusion |
| **`PersistentMessageServiceTest`** | 13 | Message persistence, conversation summaries, size validation, non-member rejection, replies, emoji reactions, idempotent sends, chronological pagination, author edit verification, unauthorized edit rejection, soft-delete filtering |
| **`PinnedMessageTest`** | 7 | Pin/unpin permissions, pin ordering and cap, broadcast of `MESSAGE_PINNED`, non-member refusal |
| **`ReadReceiptTest`** | 5 | Last-read position, `readByCount` aggregation, `READ_UPDATE` broadcast, non-member refusal, unread count |
| **`TypingServiceTest`** | 4 | Ephemeral `TYPING` broadcast, non-member refusal, 2 s throttle per member per conversation, independent start/stop throttles |
| **`UploadServiceTest`** | 6 | Storage path safety, generated names, extension sniffing from magic bytes, oversized rejection, disallowed type rejection, delete of an orphaned file |
| **`FrontendContractTest`** | 6 | `app.js` pins exactly the server's STOMP destinations, zero `innerHTML` sinks, page stays zoomable and shows loading vs empty states, the modal is an accessible dialog, settings can be opened from the profile chip and left again on a phone, and every destructive action confirms before it calls |
| **`LandingPageIntegrationTest`** | 4 | `/` serves the landing page, `/app` serves the client, every button resolves to a real route, marketing security claims match enforcement |
| **`InMemoryPresenceStoreTest`** | 11 | Presence storage contract, case-insensitivity, thread-safe register/unregister atomicity |
| **`DistributedMessageRelayTest`** | 4 | Redis Pub/Sub envelope contract: a frame published on node A is replayed into node B's broker, the origin node never echoes to itself, and unreadable or destination-less envelopes are ignored |
| **`FriendControllerIntegrationTest`** | 3 | HTTP friend-request flow end to end |
| **`FullE2EChatJourneyTest`** | 1 | Register → friend → direct chat → message journey over REST |
| **`GroupInviteLinkRestTest`** | 7 | Invite-link REST surface: create, list, revoke, preview, join, and the failure statuses over HTTP |
| **`HealthEndpointIntegrationTest`** | 2 | HTTP GET `/api/health` verifying health status reporting |
| **`UploadServingTest`** | 3 | The app boots on a non-default `UPLOAD_DIR` and a stored upload is still served back over `/uploads/**`; anonymous and traversal reads are refused |
| **`DataSeederTest`** | 6 | The opt-in seed produces a usable world on a private database: all 10 accounts log in, friendships pair and requests stay pending, the groups carry a moderator/join request/invite/block, the threads hold a pin, a forward, a reply, an edit, a soft delete and read receipts, preferences differ per account, and re-running is a no-op |
| **`DataSeederAtomicityTest`** | 1 | A seed that dies part-way through (forced with a username the seeder will collide with) leaves **no** rows behind, so the "already seeded" guard can never mistake a half-written database for a finished one |
| **`RateLimiterTest`** | 7 | Fixed-window budgets, rollover, `Retry-After`, key isolation, disabled-mode, 16-thread shared-budget atomicity |
| **`RateLimitIntegrationTest`** | 3 | Real HTTP: 429 + `RATE_LIMITED` + `Retry-After` on exhausted login/friend-request/message budgets; reads unaffected |
| **`SessionHardeningIntegrationTest`** | 3 | Cookie HttpOnly/SameSite=Lax, session id rotation on login (fixation defence), CSP / X-Frame-Options / nosniff headers |
| **`ChatWebSocketIntegrationTest`** | 4 | Real WebSocket/STOMP frames with 2–3 simultaneous clients: presence fan-out, duplicate-name refusal, oversized input, disconnect |
| **`NotificationIntegrationTest`** | 13 | Message, mention, reply, friend-request, acceptance, invitation, join-request and join-accepted notifications; preference switches suppressing delivery; per-user topic routing and ownership |
| **`WebSocketSecurityIntegrationTest`** | 3 | Anonymous and non-member subscription refusals; delivery only to authorized recipients |
| **Total** | **219** | **100% Passing (0 failures, 0 errors, 0 skipped)** |

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
   - Verify: the message appears in both windows in real time via `/topic/conversations/{id}`; Bob's window shows the typing dots while Alice types, and Alice's tick advances once Bob opens the conversation (`READ_UPDATE`).
6. **Message features:**
   - React, reply (swipe/hover → Reply), edit and delete a message; each change broadcasts live.
   - Pin a message as Alice (in a group, only owner/moderator can pin) and see it in the pin bar.
   - Search a word in the search panel; use **Load older** to page back through history.
   - Attach an image from the composer; it renders as a preview for Bob. Forward one of Alice's messages into another conversation.
7. **Groups:**
   - Alice creates a group from **My Groups**, invites Bob, and Bob accepts the invitation.
   - As owner, Alice opens **Manage**, creates an invite link, and Carol joins with the token; Alice then revokes the link and a second join attempt is refused.
   - Verify: group messages fan out to all members; the member list shows roles; blocked members cannot rejoin.
8. **Notifications & settings:**
   - Bob sends a message mentioning `@alice`; Alice's bell receives it according to her switches in **Settings → Notifications**.
   - Turning a category off stops that notification class immediately (the preference is read at publish time).
9. **Destructive actions:**
   - Remove a friend, leave a group, log out. Each opens an in-app confirmation; **Cancel** and Escape both leave state untouched, and only the confirm button performs the call.
10. **Connection resilience:**
    - With DevTools → Network set to offline, send a message (composer falls back to REST or reports the failure), then restore the connection: the client reconnects with capped backoff and refetches what it missed.
11. **Theme Switching:**
    - Click the theme toggle in Settings. The interface transitions between light and dark modes and the choice persists to `/api/settings`.

---

### Testing Distributed Multi-Instance Messaging

> **What has and has not been verified here.** The cross-instance contract is covered by automated tests
> (`DistributedMessageRelayTest` for the Redis envelope, `CrossInstanceFanoutTest` for every event type
> reaching the relay with the right destination). The steps below exercise a **real** two-node cluster and
> have **not** been run in this environment — there is no Redis server available on this machine, so
> distributed mode is code- and test-complete but not live-verified. Treat it as unverified until you run it.

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
   - Messages, edits, deletions, reactions, pins, read receipts, typing hints and notifications sent on one
     instance are relayed to the other through Redis Pub/Sub on `chat:cluster:events`.
5. **Known gaps in this path:** rate-limit counters and the DM-pair locks are still per-JVM, and a
   cross-instance race is arbitrated only by the database's unique constraints. See
   [docs/architecture.md](docs/architecture.md) §5.3 and §7.

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
| `GET` | `/api/conversations` | Get user's conversations with last-message preview and `unreadCount` | Authenticated |
| `POST` | `/api/conversations/direct` | Get or create direct conversation | Authenticated |
| `POST` | `/api/conversations/group` | Create new group conversation (title, description, avatar, privacy) | Authenticated |
| `GET` | `/api/conversations/{id}` | Get conversation details | Member |
| `GET` | `/api/conversations/{id}/members` | List members of conversation with roles | Member |
| `POST` | `/api/conversations/{id}/members` | Add member to group | Owner / Moderator |
| `DELETE` | `/api/conversations/{id}/members/{memberId}` | Remove a member | Owner / Moderator |
| `POST` | `/api/conversations/{id}/leave` | Leave the conversation | Member |
| `PUT` | `/api/conversations/{id}` | Update group title, description, avatar, privacy | Owner / Moderator |
| `DELETE` | `/api/conversations/{id}` | Delete group conversation | Owner only |
| `PUT` | `/api/conversations/{id}/archive?archive=true` | Archive or unarchive conversation | Member |
| `PUT` | `/api/conversations/{id}/read` | Mark read; records position and broadcasts `READ_UPDATE` | Member |
| `PUT` | `/api/conversations/groups/{id}/members/{userId}/role?role=` | Change a member's role | Owner only |
| `GET` | `/api/conversations/groups/discover` | Discover joinable public groups | Authenticated |
| `POST` | `/api/conversations/groups/{id}/join` | Join an open public group directly | Authenticated |
| `POST` | `/api/conversations/groups/{id}/join-requests` | Request to join a group | Authenticated |
| `GET` | `/api/conversations/groups/{id}/join-requests` | Pending requests for review | Owner / Moderator |
| `POST` | `/api/conversations/groups/{id}/join-requests/{requestId}/accept` | Accept a join request | Owner / Moderator |
| `POST` | `/api/conversations/groups/{id}/join-requests/{requestId}/reject` | Reject a join request | Owner / Moderator |
| `DELETE` | `/api/conversations/groups/{id}/join-requests/{requestId}` | Withdraw own request | Requester |
| `GET` | `/api/conversations/join-requests` | My outgoing join requests | Authenticated |
| `POST` | `/api/conversations/groups/{id}/invitations` | Invite users to a group | Owner / Moderator |
| `GET` | `/api/conversations/invitations` | My group invitations | Authenticated |
| `GET` | `/api/conversations/groups/{id}/invitations` | Invitations sent into a group | Owner / Moderator |
| `POST` | `/api/conversations/invitations/{id}/accept` | Accept an invitation | Invitee |
| `POST` | `/api/conversations/invitations/{id}/reject` | Reject an invitation | Invitee |
| `DELETE` | `/api/conversations/invitations/{id}` | Cancel an invitation | Inviter |
| `POST` | `/api/conversations/groups/{id}/blocks` | Block a member from rejoining | Owner / Moderator |
| `DELETE` | `/api/conversations/groups/{id}/blocks/{userId}` | Unblock | Owner / Moderator |
| `GET` | `/api/conversations/groups/{id}/blocks` | List blocked users | Owner / Moderator |
| `POST` | `/api/conversations/groups/{id}/invite-links` | Create an invite link; optional `expiresInSeconds` (1 min–30 days) and `maxUses` | Owner / Moderator |
| `GET` | `/api/conversations/groups/{id}/invite-links` | List active invite links | Owner / Moderator |
| `DELETE` | `/api/conversations/groups/{id}/invite-links/{linkId}` | Revoke an invite link | Owner / Moderator |
| `POST` | `/api/conversations/invite-links/preview` | Preview the group behind a token `{"token":"…"}` | Authenticated |
| `POST` | `/api/conversations/invite-links/join` | Join via token `{"token":"…"}`; token never appears in a URL | Authenticated |

#### Messages
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/conversations/{id}/messages?page=&size=` | Paginate history, newest page first, chronologically ordered | Member |
| `POST` | `/api/conversations/{id}/messages` | Send message; accepts `replyToId`, optional `attachment`, and idempotent `clientMessageId`; rate limited per sender | Member |
| `POST` | `/api/conversations/{id}/messages/forward` | Copy an existing message here `{"messageId":"…","clientMessageId":"…"}` | Member of source **and** destination |
| `GET` | `/api/conversations/{id}/messages/search?q=&limit=` | Substring search within one conversation | Member |
| `GET` | `/api/conversations/{id}/pinned` | Pinned messages of a conversation | Member |
| `PUT` | `/api/messages/{msgId}` | Edit existing message | Author only |
| `DELETE` | `/api/messages/{msgId}` | Soft delete message | Author / Owner |
| `POST` | `/api/messages/{msgId}/reactions` | Toggle an emoji reaction | Member |
| `PUT` | `/api/messages/{msgId}/pin` | Pin or unpin `{"pinned":true}` | Any member in a direct chat; in a group, pinning is Owner / Moderator (the author may unpin their own) |

#### Uploads (`/api/uploads`)
| Method | Path | Description | Access |
|---|---|---|---|
| `POST` | `/api/uploads/avatar` | Multipart avatar upload; returns `{"url":"/uploads/…"}` | Authenticated |
| `POST` | `/api/uploads/media` | Multipart message attachment; returns url, name, MIME type and size | Authenticated |

Uploads are type-sniffed from magic bytes (PNG, JPEG, GIF, WebP, PDF only), capped by `UPLOAD_MAX_BYTES`, stored with a generated name outside the source tree, and never served with a client-supplied filename.

#### Settings (`/api/settings`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/settings` | Theme, notification master switch, sound, online visibility, four notification category switches | Authenticated |
| `PUT` | `/api/settings` | Persist the caller's preferences | Authenticated |

#### Health (`/api/health`)
| Method | Path | Description | Access |
|---|---|---|---|
| `GET` | `/api/health` | `{"status":"UP","onlineUsers":N,"timestamp":…}` — deliberately tiny so it stays usable as a load-balancer probe | Public |

---

### WebSocket / STOMP Destinations

| Direction | Destination | Purpose | Payload |
|---|---|---|---|
| **Client → Server** | `/app/chat.join` | Join chat and register presence | `{"username": "alice"}` |
| | `/app/conversations/{id}/send` | Send a message into a persisted conversation | `{"content": "Hello!", "replyToId": "<uuid|null>", "clientMessageId": "<uuid>"}` — `clientMessageId` makes retries idempotent; the server assigns sender and timestamps |
| | `/app/conversations/{id}/typing` | Announce typing state | `{"typing": true}` — throttled server-side to one frame per member per 2 s |
| **Server → Client** | `/topic/users` | Presence changes & online list | `UserPresenceResponse`: the join/leave event **and** the complete sorted online list |
| | `/topic/conversations/{id}` | Persistent conversation events | `NEW_MESSAGE`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_REACTED`, `MESSAGE_PINNED`, `READ_UPDATE`, `TYPING` — published only after the DB commit; subscriptions are refused (403) for non-members |
| | `/topic/notifications/{userId}` | Live notifications for one user | `NotificationDto{type, category, actorId, actorUsername, conversationId, messageId, title, body, createdAt}` — a topic (not `/user/queue`) so the cross-instance relay can carry it; `StompSubscriptionGuard` refuses any session that is not that user |
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
| `SPRING_FLYWAY_ENABLED` | `true` | Flyway is the sole schema owner (`ddl-auto: validate` — structural drift fails startup; [architecture §6](docs/architecture.md#6-security--what-the-code-does-and-what-it-does-not) covers the one class it cannot see) |
| `SPRING_REDIS_HOST` | `localhost` | Redis server hostname |
| `SPRING_REDIS_PORT` | `6379` | Redis server port |
| `CHAT_DISTRIBUTED_ENABLED` | `false` | Enable Redis cluster presence & Pub/Sub relay |
| `CHAT_SEED_ENABLED` | unset (`false`) | Insert the demo world on startup (see [Seeding demo data](#seeding-demo-data-opt-in-development-only)). Absent ⇒ the seeder bean is not in the context |
| `CHAT_RATE_LIMIT_ENABLED` | `true` | Master switch for the fixed-window rate limiter |
| `RATE_LIMIT_LOGIN` | `10` | Login attempts per minute per IP |
| `RATE_LIMIT_REGISTER` | `5` | Registrations per minute per IP |
| `RATE_LIMIT_FRIEND_REQUESTS` | `20` | Friend requests per minute per user |
| `RATE_LIMIT_MESSAGES` | `60` | Message sends per minute per sender (covers REST **and** STOMP) |
| `SESSION_TIMEOUT` | `8h` | Server-side session lifetime |
| `COOKIE_SECURE` | `false` | Set `true` behind TLS so the session cookie is `Secure` |
| `UPLOAD_DIR` | `./uploads` | Where uploads are written (served under `/uploads/**`) |
| `UPLOAD_MAX_BYTES` | `10485760` | Per-file cap `UploadService` applies after reading the bytes |
| `UPLOAD_MAX_FILE_SIZE` | `10MB` | Spring multipart per-file limit (must be ≥ `UPLOAD_MAX_BYTES`) |
| `UPLOAD_MAX_REQUEST_SIZE` | `12MB` | Spring multipart per-request limit |

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
    │   │   ├── api/                    # REST controllers (Auth, Users, Friends, Convs, Messages, Uploads, Settings, Health)
    │   │   ├── application/            # Business services (Auth, Friend, Conv, Message, Typing, Notification, Invite links, Moderation, Upload, Presence)
    │   │   ├── config/                 # WebSocket, CORS/resource, property configuration
    │   │   ├── domain/                 # Domain entities, enums, and value objects
    │   │   ├── dto/                    # Request/Response data transfer objects
    │   │   ├── exception/              # Global error handling and ChatException
    │   │   ├── infrastructure/         # Repositories; InMemory + Redis presence stores; Redis Pub/Sub relay
    │   │   ├── security/               # SecurityConfig (sessions, CSP), RateLimiter + filter, StompSubscriptionGuard
    │   │   ├── seed/                   # DataSeeder: opt-in demo world, absent unless chat.seed.enabled=true
    │   │   └── websocket/              # STOMP controllers and connection event listeners
    │   └── resources/
    │       ├── application.yml         # Application configuration
    │       ├── db/migration/           # Flyway SQL schema migrations V1–V6 (sole schema owner)
    │       └── static/                 # ChatSphere vanilla web frontend
    │           ├── landing.html        # Marketing landing page served at /
    │           ├── index.html          # Chat layout and modals (served at /app)
    │           ├── css/                # styles.css (app) and landing.css (landing page)
    │           └── js/app.js           # Frontend controller: hand-rolled STOMP client, reconnect, safe DOM rendering
    └── test/java/com/example/chat/
        ├── application/                # Service, engagement-feature, distributed-fan-out & concurrency race tests
        ├── frontend/                   # Contract, XSS-safety & landing page tests
        ├── infrastructure/             # Presence store & Redis relay envelope tests
        ├── integration/                # REST integration tests
        ├── security/                   # Rate limiter & session hardening tests
        └── websocket/                  # Full-duplex WebSocket STOMP, notification & authorization tests
```
