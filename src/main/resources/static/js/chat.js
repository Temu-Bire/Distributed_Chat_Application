/**
 * Distributed Chat — browser client.
 *
 * Responsibilities, deliberately kept to the presentation layer:
 *   - own the STOMP connection lifecycle and reconnection,
 *   - translate server DTOs into DOM nodes,
 *   - send user intent to /app/chat.* and nothing more.
 *
 * It performs no chat rules of its own beyond cheap local checks that give
 * immediate feedback; the server remains the authority for every rule.
 *
 * Security note: all user-generated content is rendered with textContent.
 * Message content is never inserted as HTML, so a message such as
 * `<img src=x onerror=...>` is displayed as text.
 */
(function () {
    'use strict';

    /* ── Configuration ───────────────────────────────────────────────── */

    const ENDPOINT         = '/ws';
    const RECONNECT_MS     = 4000;
    const HEARTBEAT_MS     = 10000;
    const CONNECT_TIMEOUT  = 8000;
    const MAX_USERNAME     = 20;
    const MAX_CONTENT      = 2000;
    const MAX_RENDERED     = 500;   // cap DOM growth in a long-lived session
    const JOIN_RETRY_MS    = 4000;  // wait for presence confirmation of our join
    const JOIN_ATTEMPTS    = 2;     // one retry keeps the client self-healing

    /** Must match MessageRouter on the server. */
    const DEST = Object.freeze({
        JOIN:        '/app/chat.join',
        SEND:        '/app/chat.send',
        PRIVATE:     '/app/chat.private',
        PUBLIC:      '/topic/public',
        USERS:       '/topic/users',
        PRIVATE_BOX: '/user/queue/private',
        ERROR_BOX:   '/user/queue/errors'
    });

    /* ── DOM ─────────────────────────────────────────────────────────── */

    const byId = (id) => document.getElementById(id);

    const dom = {
        health:         byId('server-health'),
        status:         byId('connection-status'),
        statusText:     byId('connection-text'),
        identity:       byId('identity'),
        leaveButton:    byId('leave-button'),

        joinView:       byId('join-view'),
        joinForm:       byId('join-form'),
        usernameInput:  byId('username-input'),
        joinError:      byId('join-error'),

        chatView:       byId('chat-view'),
        messages:       byId('messages'),
        chatError:      byId('chat-error'),
        messageForm:    byId('message-form'),
        recipientSelect: byId('recipient-select'),
        messageInput:   byId('message-input'),

        userList:       byId('user-list'),
        userCount:      byId('user-count')
    };

    /* ── State ───────────────────────────────────────────────────────── */

    const state = {
        client: null,
        /** Display name confirmed by the server; null while not joined. */
        joinedAs: null,
        /** Name we asked for, until presence confirms it. */
        pendingName: null,
        /** Handles for the current STOMP session only. */
        subscriptions: []
    };

    /** Timer that verifies our join was accepted; see armJoinWatchdog(). */
    let joinWatchdog = null;

    /* ── Connection lifecycle ────────────────────────────────────────── */

    function wsUrl() {
        const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
        return scheme + '://' + window.location.host + ENDPOINT;
    }

    function buildClient() {
        const client = new StompJs.Client({
            brokerURL: wsUrl(),
            reconnectDelay: RECONNECT_MS,
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            connectionTimeout: CONNECT_TIMEOUT
        });

        client.onConnect = onConnected;
        client.onWebSocketClose = onSocketClosed;
        client.onWebSocketError = () => setStatus('offline', 'Connection error');
        client.onStompError = (frame) => {
            // A STOMP ERROR frame means the session is dead; report and stop.
            console.error('STOMP error', frame.headers, frame.body);
            showChatError('The chat connection failed. Please rejoin.');
        };
        return client;
    }

    async function join(username) {
        hideAlert(dom.joinError);

        // Start from a clean client so a retry never inherits a half-open socket.
        await teardown();
        state.pendingName = username;

        setStatus('pending', 'Connecting…');
        state.client = buildClient();
        state.client.activate();
    }

    async function leave() {
        await teardown();
        showJoinView();
    }

    /** Closes the client and forgets all per-connection state. */
    async function teardown() {
        clearJoinWatchdog();

        const client = state.client;
        state.client = null;
        state.joinedAs = null;
        state.pendingName = null;
        state.subscriptions = [];

        if (client) {
            try {
                await client.deactivate();
            } catch (error) {
                console.warn('Ignoring error while closing the connection', error);
            }
        }
        setStatus('offline', 'Disconnected');
    }

    function onConnected() {
        setStatus('online', 'Connected');
        subscribeAll();

        // Re-announce on reconnect. Subscribing first means the presence
        // broadcast caused by this join is delivered to us too.
        const name = state.joinedAs || state.pendingName;
        if (name) {
            publishJoin(name);
            armJoinWatchdog(name, JOIN_ATTEMPTS);
        }
    }

    function publishJoin(username) {
        state.client.publish({
            destination: DEST.JOIN,
            body: JSON.stringify({ username: username })
        });
    }

    /**
     * Confirms the join actually took effect.
     *
     * STOMP has no acknowledgement for SUBSCRIBE, so in principle the server's
     * broadcast of our own join could be published before our subscription was
     * registered, leaving the client waiting forever. Rather than guess, we
     * re-send the join once if nothing arrives; a second silent timeout is a
     * real failure and is surfaced to the user instead of spinning.
     */
    function armJoinWatchdog(username, attemptsLeft) {
        clearJoinWatchdog();
        joinWatchdog = window.setTimeout(() => {
            joinWatchdog = null;
            if (state.joinedAs || !state.client || !state.client.connected) {
                return;
            }
            if (attemptsLeft > 0) {
                publishJoin(username);
                armJoinWatchdog(username, attemptsLeft - 1);
                return;
            }
            dom.joinError.textContent = 'No response from the server. Please try again.';
            dom.joinError.hidden = false;
            state.pendingName = null;
            void teardown();
        }, JOIN_RETRY_MS);
    }

    function clearJoinWatchdog() {
        if (joinWatchdog !== null) {
            window.clearTimeout(joinWatchdog);
            joinWatchdog = null;
        }
    }

    function onSocketClosed() {
        // stompjs reconnects on its own while the client is still active.
        setStatus('offline', state.client && state.client.active ? 'Reconnecting…' : 'Disconnected');
    }

    /**
     * Subscribes the four destinations for the current session.
     *
     * Called once per successful connection. STOMP subscriptions belong to the
     * session, so a reconnect produces a fresh session and must subscribe
     * again; reusing the old handles would leave the client silent. Because the
     * handles are replaced wholesale and never appended across reconnects,
     * messages are never delivered twice.
     */
    function subscribeAll() {
        state.subscriptions = [
            state.client.subscribe(DEST.PUBLIC,    (frame) => onPublicMessage(parse(frame))),
            state.client.subscribe(DEST.USERS,     (frame) => onPresence(parse(frame))),
            state.client.subscribe(DEST.PRIVATE_BOX, (frame) => onPrivateMessage(parse(frame))),
            state.client.subscribe(DEST.ERROR_BOX, (frame) => onError(parse(frame)))
        ];
    }

    /* ── Server → UI ─────────────────────────────────────────────────── */

    function parse(frame) {
        try {
            return JSON.parse(frame.body);
        } catch (error) {
            console.warn('Ignoring a frame that was not valid JSON', error);
            return null;
        }
    }

    function onPublicMessage(message) {
        if (!message) {
            return;
        }
        appendMessage({
            time: message.timestamp,
            sender: message.sender,
            text: message.content,
            modifier: message.sender === state.joinedAs ? 'msg--own' : null
        });
    }

    function onPrivateMessage(message) {
        if (!message) {
            return;
        }
        const outgoing = message.sender === state.joinedAs;
        appendMessage({
            time: message.timestamp,
            sender: outgoing ? 'you → ' + message.recipient : 'from ' + message.sender,
            text: message.content,
            modifier: 'msg--private',
            tag: 'private'
        });
    }

    function onPresence(presence) {
        if (!presence) {
            return;
        }
        const users = presence.users || [];
        renderUsers(users);

        if (presence.event === 'JOIN' || presence.event === 'LEAVE') {
            appendMessage({
                time: presence.timestamp,
                text: presence.username + (presence.event === 'JOIN' ? ' joined the chat' : ' left the chat'),
                modifier: 'msg--system'
            });
        }

        // Our own join is confirmed by seeing our name in the user list.
        if (!state.joinedAs && state.pendingName && containsIgnoreCase(users, state.pendingName)) {
            state.joinedAs = state.pendingName;
            state.pendingName = null;
            hideAlert(dom.joinError);
            showChatView();
        }
    }

    function onError(error) {
        if (!error) {
            return;
        }
        const text = error.message || 'The server rejected the request.';

        if (!state.joinedAs) {
            // The join was refused (blank, too long or already taken). Tear the
            // connection down outside the frame callback, leaving the reason on
            // screen so the user can pick another name.
            state.pendingName = null;
            dom.joinError.textContent = text;
            dom.joinError.hidden = false;
            window.setTimeout(() => { void teardown(); }, 0);
            return;
        }
        showChatError(text);
    }

    /* ── Sending ─────────────────────────────────────────────────────── */

    function sendMessage() {
        const text = dom.messageInput.value;
        if (text.trim() === '') {
            return;
        }
        if (!state.client || !state.client.connected || !state.joinedAs) {
            showChatError('You are not connected.');
            return;
        }
        if (text.length > MAX_CONTENT) {
            showChatError('Messages must be at most ' + MAX_CONTENT + ' characters.');
            return;
        }

        const recipient = dom.recipientSelect.value;
        const destination = recipient ? DEST.PRIVATE : DEST.SEND;
        const body = recipient
            ? { recipient: recipient, content: text }
            : { content: text };

        state.client.publish({ destination: destination, body: JSON.stringify(body) });

        dom.messageInput.value = '';
        dom.messageInput.focus();
    }

    /* ── Rendering ───────────────────────────────────────────────────── */

    function appendMessage(options) {
        const item = document.createElement('li');
        item.className = 'msg' + (options.modifier ? ' ' + options.modifier : '');

        const time = document.createElement('span');
        time.className = 'msg__time';
        time.textContent = formatTime(options.time);
        item.append(time);

        if (options.tag) {
            const tag = document.createElement('span');
            tag.className = 'msg__tag';
            tag.textContent = options.tag;
            item.append(tag);
        }

        if (options.sender) {
            const sender = document.createElement('span');
            sender.className = 'msg__sender';
            sender.textContent = options.sender;
            item.append(sender);
        }

        const text = document.createElement('span');
        text.className = 'msg__text';
        text.textContent = options.text;
        item.append(text);

        const pinnedToBottom = isScrolledToBottom();
        dom.messages.append(item);

        while (dom.messages.childElementCount > MAX_RENDERED) {
            dom.messages.firstElementChild.remove();
        }
        if (pinnedToBottom) {
            dom.messages.scrollTop = dom.messages.scrollHeight;
        }
    }

    function renderUsers(users) {
        dom.userCount.textContent = String(users.length);
        dom.userList.replaceChildren();

        if (users.length === 0) {
            const empty = document.createElement('li');
            empty.className = 'users__empty';
            empty.textContent = 'Nobody online.';
            dom.userList.append(empty);
        } else {
            for (const name of users) {
                const item = document.createElement('li');
                item.className = 'users__item' + (name === state.joinedAs ? ' users__item--self' : '');
                item.textContent = name;
                dom.userList.append(item);
            }
        }
        renderRecipients(users);
    }

    function renderRecipients(users) {
        const previous = dom.recipientSelect.value;
        dom.recipientSelect.replaceChildren();

        const everyone = document.createElement('option');
        everyone.value = '';
        everyone.textContent = 'Everyone';
        dom.recipientSelect.append(everyone);

        for (const name of users) {
            if (name === state.joinedAs) {
                continue;
            }
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            dom.recipientSelect.append(option);
        }

        // Keep the current choice if that person is still online.
        dom.recipientSelect.value = containsIgnoreCase(users, previous) ? previous : '';
        syncComposerMode();
    }

    function syncComposerMode() {
        const recipient = dom.recipientSelect.value;
        dom.messageForm.classList.toggle('is-private', recipient !== '');
        dom.messageInput.placeholder = recipient
            ? 'Private message to ' + recipient + '…'
            : 'Write a message…';
    }

    function showChatView() {
        clearJoinWatchdog();
        dom.joinView.hidden = true;
        dom.chatView.hidden = false;
        dom.identity.textContent = 'Signed in as ' + state.joinedAs;
        dom.leaveButton.hidden = false;
        dom.messageInput.focus();
    }

    function showJoinView() {
        dom.chatView.hidden = true;
        dom.joinView.hidden = false;
        dom.identity.textContent = '';
        dom.leaveButton.hidden = true;
        dom.messages.replaceChildren();
        hideAlert(dom.chatError);
        renderUsers([]);
    }

    function setStatus(kind, text) {
        dom.status.className = 'chip chip--' + kind;
        dom.statusText.textContent = text;
    }

    function showChatError(message) {
        dom.chatError.textContent = message;
        dom.chatError.hidden = false;
        window.setTimeout(() => { dom.chatError.hidden = true; }, 6000);
    }

    function hideAlert(element) {
        element.hidden = true;
        element.textContent = '';
    }

    function formatTime(isoTimestamp) {
        const date = isoTimestamp ? new Date(isoTimestamp) : new Date();
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    function isScrolledToBottom() {
        return dom.messages.scrollHeight - dom.messages.scrollTop - dom.messages.clientHeight < 40;
    }

    function containsIgnoreCase(list, value) {
        const needle = String(value).toLowerCase();
        return list.some((entry) => String(entry).toLowerCase() === needle);
    }

    /* ── Health indicator ────────────────────────────────────────────── */

    async function refreshHealth() {
        try {
            const response = await fetch('/api/health', { headers: { Accept: 'application/json' } });
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            const health = await response.json();
            dom.health.textContent = 'server: ' + health.status + ' · ' + health.onlineUsers + ' online';
        } catch (error) {
            dom.health.textContent = 'server: unreachable';
        }
    }

    /* ── Wiring ──────────────────────────────────────────────────────── */

    dom.joinForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const username = dom.usernameInput.value.trim();

        if (username === '') {
            dom.joinError.textContent = 'Enter a username.';
            dom.joinError.hidden = false;
            return;
        }
        if (username.length > MAX_USERNAME) {
            dom.joinError.textContent = 'Username must be at most ' + MAX_USERNAME + ' characters.';
            dom.joinError.hidden = false;
            return;
        }
        void join(username);
    });

    dom.messageForm.addEventListener('submit', (event) => {
        event.preventDefault();
        sendMessage();
    });

    dom.leaveButton.addEventListener('click', () => { void leave(); });

    dom.recipientSelect.addEventListener('change', syncComposerMode);

    window.addEventListener('beforeunload', () => {
        // Best effort: close cleanly so the server removes us immediately
        // instead of waiting for a heartbeat to time the session out.
        if (state.client) {
            state.client.deactivate({ force: true });
        }
    });

    setStatus('offline', 'Disconnected');
    showJoinView();
    void refreshHealth();
    window.setInterval(refreshHealth, 30000);
})();
