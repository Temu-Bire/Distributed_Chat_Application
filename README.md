# Distributed Chat Application

A browser-based group chat built with **Java 21**, **Spring Boot 3**, and
**WebSocket / STOMP**, with a plain HTML/CSS/JavaScript frontend.

It is a ground-up rewrite of the original TCP-socket + Swing assignment into a
modular, testable, web-based application. The original sources are preserved
unchanged under [`legacy/tcp/`](legacy/tcp/) — see [The original TCP version](#the-original-tcp-version).

---

## Documentation

| Document | What is in it |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | **Mermaid diagram**, per-layer responsibilities, endpoint and destination reference, concurrency explanation, security posture, architecture decisions, and the roadmap for running this on several machines |
| `README.md` (this file) | How to build, run, test, and try it in two browsers |

---

## Requirements

- **JDK 21** (the project targets Java 21).
- **No Maven install needed** — the Maven Wrapper (`mvnw` / `mvnw.cmd`) is
  committed and downloads Maven 3.9.16 on first use.
- **No Node, no npm, no frontend build step.** The page is static files served
  by Spring Boot.

`java` does not have to be on `PATH`; setting `JAVA_HOME` is enough.

**Windows PowerShell:**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'   # <- your JDK 21 path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"                        # optional, so 'java' also resolves
```

**macOS / Linux (bash):**

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
```

Check both are picked up:

```powershell
.\mvnw.cmd -v
```

You should see `Apache Maven 3.9.16` and `Java version: 21…`.

---

## Quick start

```powershell
# run the app (dev mode)
.\mvnw.cmd -B spring-boot:run
```

Then open **<http://localhost:8080>** in a browser.

Stop it with `Ctrl+C`.

### If port 8080 is already in use

Some machines have another web server on 8080. Run the app on a different
port instead:

```powershell
.\mvnw.cmd -B spring-boot:run "-Dspring-boot.run.arguments=--server.port=8090"
```

then open **<http://localhost:8090>**. No configuration change is needed:
the browser is talking to the same host:port that served the page, and the
origin check permits same-origin requests by default. See
[`docs/architecture.md` §6](docs/architecture.md#6-security--what-the-code-does-and-what-it-does-not).

### Build a runnable jar

```powershell
.\mvnw.cmd -B package
& "$env:JAVA_HOME\bin\java.exe" -jar target\chat-app-1.0.0-SNAPSHOT.jar
```

---

## Testing

```powershell
.\mvnw.cmd -B test
```

**58 tests, all of which assert behaviour rather than just starting the context:**

| Test | What it proves |
|---|---|
| `ChatServiceTest` (27) | Username rules, duplicate rejection, message length limits, offline recipients, public routing, private routing, disconnect cleanup, error codes |
| `DuplicateUsernameConcurrencyTest` (3) | 32 threads racing to claim one name (including mixed case): exactly one wins |
| `InMemoryPresenceStoreTest` (11) | The presence store's contract, including atomicity |
| `FrontendContractTest` (2) | The shipped `chat.js` targets exactly the destinations `MessageRouter` serves, and never assigns HTML |
| `HealthEndpointIntegrationTest` (2) | The REST health surface |
| `ChatWebSocketIntegrationTest` (13) | Real sockets with 2–3 simultaneous clients: join/leave presence, public round trip, private delivery to recipient **and** sender only, a spoofed `sender` field being ignored, blank/oversized input, duplicate names, and an explicit assertion that a private message **never** reaches `/topic/public` |

Run a single test class:

```powershell
.\mvnw.cmd -B test "-Dtest=ChatWebSocketIntegrationTest"
```

---

## Try it with two browsers

This is the manual check that matters, because it exercises the real page in a
real browser rather than the test harness.

1. **Start the server**
   ```powershell
   .\mvnw.cmd -B spring-boot:run
   ```
   Wait for `Started ChatApplication`.

2. **First client** — open <http://localhost:8080>.
   - The chip at the top right should read `server: UP – 0 online`.
   - Enter a name (for example `alice`) and click **Connect**.
   - The status chip turns green/`Connected`, the header shows
     `Signed in as alice`, and the sidebar's `Online` count reads `1` with
     `alice` listed beneath it.

3. **Second client** — open a **new window or a private/incognito tab** at
   <http://localhost:8080> and join as `bob`.
   - Both windows now show **Online 2**.
   - Both histories show a system line `bob joined the chat`.

4. **Public message** — in the `alice` window type `hello from alice` and press
   `Enter` (or click **Send**).
   - Both windows show it, with the sender name and a timestamp.
   - In alice's own window it is highlighted as her own message.

5. **Private message** — in the `bob` window, pick `alice` in the
   **recipient** dropdown (the placeholder changes to `Private message to
   alice…`), type `only for alice`, and send.
   - Bob's history shows it tagged `private` as `you → alice`.
   - Alice's history shows it tagged `private` as `from bob`.
   - **Open a third tab** as a third name first, if you want to confirm the
     isolation: that third client must not see the message at all.

6. **Duplicate name** — in a fourth tab try to join as `alice` (any letter
   case).
   - The join screen stays, and an error explains the name is already in use.

7. **Disconnect** — close one window.
   - The other window immediately shows `alice left the chat` and the user
     count drops.

8. **Reconnection** — with `F5` on a client that was in the chat, it returns to
   the join screen (state is not restored); rejoining with the same name works
   as soon as the previous connection has been cleaned up.

---

## Project structure

```
.
├── mvnw, mvnw.cmd, .mvn/      Maven Wrapper (pinned Maven 3.9.16)
├── pom.xml                    Spring Boot 3.5.16, Java 21
├── README.md                  this file
├── REPORT_TEMPLATE.md         assignment template (unmodified)
├── docs/
│   └── architecture.md        diagram, layers, endpoints, roadmap
├── legacy/
│   └── tcp/                   original ChatServer.java / ChatClient.java
└── src/
    ├── main/
    │   ├── java/com/example/chat/
    │   │   ├── ChatApplication.java      Spring Boot entry point
    │   │   ├── api/                      REST adapter (health)
    │   │   ├── application/              rules and orchestration
    │   │   │   ├── ChatService           join / send / private / leave
    │   │   │   ├── PresenceService       who is online
    │   │   │   ├── PresenceStore         port (interface)
    │   │   │   └── MessageRouter         only owner of destination names
    │   │   ├── config/                   broker, executors, handshake, CORS
    │   │   ├── domain/                   ChatMessage, ChatUser, MessageType
    │   │   ├── dto/                      wire payloads + validation
    │   │   ├── exception/                error contract (STOMP and REST)
    │   │   ├── infrastructure/           InMemoryPresenceStore
    │   │   └── websocket/                STOMP controller + lifecycle events
    │   └── resources/
    │       ├── application.yml           port, logging, documented overrides
    │       └── static/                   the browser client (no build step)
    │           ├── index.html
    │           ├── css/styles.css
    │           └── js/
    │               ├── chat.js           page logic
    │               └── vendor/           @stomp/stompjs 7.1.1 (Apache-2.0)
    └── test/java/com/example/chat/
        ├── application/                   unit tests
        ├── frontend/                      browser/server contract tests
        ├── infrastructure/                presence store tests
        ├── integration/                   shared STOMP test client
        └── websocket/                     end-to-end WebSocket tests
```

### Layering rules

- **`domain/`** knows nothing about Spring or JSON.
- **`application/`** owns the rules; it never sees HTTP or WebSocket types.
- **`websocket/` and `api/`** translate a transport frame into a service call
  and contain no business logic of their own.
- **`infrastructure/`** holds concrete implementations of the ports declared in
  `application/`.
- **`MessageRouter`** is the single place that knows destination names.

---

## How it works (short version)

| Direction | Destination | Purpose |
|---|---|---|
| client → server | `/app/chat.join` | Register a display name |
| | `/app/chat.send` | Public message |
| | `/app/chat.private` | Message to one person |
| server → client | `/topic/public` | Public messages |
| | `/topic/users` | The complete online list, plus join/leave |
| | `/user/queue/private` | Private messages, one connection only |
| | `/user/queue/errors` | Failures caused by that connection |

The sender of a message is taken from the connection, never from the payload,
so a client cannot post as somebody else. Message content is rendered with
`textContent`, so a message containing HTML is displayed literally.

Full detail: [`docs/architecture.md`](docs/architecture.md).

---

## Known limitations

These are deliberate MVP boundaries, not oversights:

- **No authentication.** Joining needs only a display name — anyone can take an
  unused name. Nothing in transit is encrypted (no TLS). Do not put this
  somewhere public in its current form.
- **No persistence.** Restarting the server clears everything; refreshing a
  browser loses that tab's history. There is no message store and no
  offline delivery.
- **Single instance.** Presence and the message broker live in one JVM, so
  running two copies would split the chat in half.
- **No rate limiting.** A single client can flood the server.
- **Limits:** usernames are at most 20 characters, messages at most 2000.
- **A name is held until its connection is cleaned up.** There is no
  kick/reclaim flow: if a stale session lingers, joining under that name fails
  until it drops. (A reconnect token would be the fix; see the roadmap.)
- **Message ordering is not guaranteed** across the bounded worker pools, and
  nothing in the chat rules depends on it.
- **History is per-tab and capped** at 500 rendered messages to keep the DOM
  bounded in a long session.

---

## Next steps

The staged roadmap — in the order they should be done — is in
[`docs/architecture.md` §7](docs/architecture.md#7-path-to-a-distributed-deployment).
In short:

1. TLS and real authentication (the handshake handler already accepts a real
   `Principal`, so the seam exists).
2. An external STOMP broker, so several instances share one topic.
3. Presence in a shared store with TTL-based expiry.
4. Load balancer.
5. Persistence with idempotent writes on `messageId`.
6. Metrics, alerting, and rate limiting.

Doing the load balancer before the broker produces a cluster that looks
functional while silently dropping messages — which is why the order matters.

---

## Configuration reference

Everything is under `chat.*` in
[`src/main/resources/application.yml`](src/main/resources/application.yml),
with defaults in `ChatProperties`. The common override:

```powershell
.\mvnw.cmd -B spring-boot:run "-Dspring-boot.run.arguments=--server.port=8090"
```

Multiple settings are space-separated inside the same quoted string. The
allow-listed browser origins are set with `chat.allowed-origins`.

---

## The original TCP version

The first version of this assignment was a Java TCP socket server and Swing
client. It is kept intact in [`legacy/tcp/`](legacy/tcp/) and is no longer part
of the Maven build. To run it:

```powershell
Set-Location legacy\tcp
& "$env:JAVA_HOME\bin\javac.exe" ChatServer.java ChatClient.java
& "$env:JAVA_HOME\bin\java.exe" ChatServer          # terminal 1
& "$env:JAVA_HOME\bin\java.exe" ChatClient          # terminal 2 (repeat for more clients)
```
