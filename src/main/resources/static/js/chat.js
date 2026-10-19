/**
 * Distributed ChatApp — browser client.
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

    /* ── Avatar Colors ───────────────────────────────────────────────── */

    const AVATAR_PALETTE = [
        { bg: '#8b5cf6', text: '#ffffff' }, // Purple (Alice)
        { bg: '#0284c7', text: '#ffffff' }, // Teal / Sky (Bob)
        { bg: '#10b981', text: '#ffffff' }, // Emerald
        { bg: '#f59e0b', text: '#ffffff' }, // Amber
        { bg: '#ec4899', text: '#ffffff' }, // Pink
        { bg: '#6366f1', text: '#ffffff' }, // Indigo
        { bg: '#14b8a6', text: '#ffffff' }, // Teal
        { bg: '#f97316', text: '#ffffff' }  // Orange
    ];

    function getAvatarColor(name) {
        if (!name) return AVATAR_PALETTE[0];
        const lower = name.toLowerCase();
        if (lower === 'alice') return AVATAR_PALETTE[0];
        if (lower === 'bob') return AVATAR_PALETTE[1];
        let hash = 0;
        for (let i = 0; i < name.length; i++) {
            hash = (hash << 5) - hash + name.charCodeAt(i);
            hash |= 0;
        }
        const index = Math.abs(hash) % AVATAR_PALETTE.length;
        return AVATAR_PALETTE[index];
    }

    /* ── DOM References ──────────────────────────────────────────────── */

    const byId = (id) => document.getElementById(id);

    const dom = {
        // App Shell
        sidebar:                byId('sidebar'),
        sidebarBackdrop:        byId('sidebar-backdrop'),
        sidebarCloseBtn:        byId('sidebar-close-btn'),
        mobileMenuBtn:          byId('mobile-menu-btn'),

        // Header
        headerAvatar:           byId('header-avatar'),
        headerGroupIcon:        byId('header-group-icon'),
        headerUserInitial:      byId('header-user-initial'),
        activeConversationTitle: byId('active-conversation-title'),
        activeConversationSubtitle: byId('active-conversation-subtitle'),
        serverHealth:           byId('server-health'),
        connectionStatus:       byId('connection-status'),
        connectionText:         byId('connection-text'),
        connectionTextSidebar:  byId('connection-text-sidebar'),
        reconnectButton:        byId('reconnect-button'),
        themeToggleBtn:         byId('theme-toggle-btn'),

        // Sidebar Navigation
        navPublicBtn:           byId('nav-public-btn'),
        navPrivateBtn:          byId('nav-private-btn'),
        publicUnreadBadge:      byId('public-unread-badge'),
        privateUnreadBadge:     byId('private-unread-badge'),

        // Online Users
        userCount:              byId('user-count'),
        userSearchInput:        byId('user-search-input'),
        searchClearBtn:         byId('search-clear-btn'),
        userList:               byId('user-list'),

        // Profile & Settings
        currentUserAvatar:      byId('current-user-avatar'),
        identity:               byId('identity'),
        profileSettingsBtn:     byId('profile-settings-btn'),
        settingsMenu:           byId('settings-menu'),
        reconnectMenuBtn:       byId('reconnect-menu-btn'),
        leaveButton:            byId('leave-button'),

        // Join View
        joinView:               byId('join-view'),
        joinForm:               byId('join-form'),
        joinAvatarPreview:      byId('join-avatar-preview'),
        usernameInput:          byId('username-input'),
        joinError:              byId('join-error'),
        joinButton:             byId('join-button'),
        joinBtnText:            byId('join-btn-text'),

        // Chat View
        chatView:               byId('chat-view'),
        messages:               byId('messages'),
        scrollBottomBtn:        byId('scroll-bottom-btn'),
        chatError:              byId('chat-error'),

        // Composer
        messageForm:            byId('message-form'),
        recipientSelect:        byId('recipient-select'),
        emojiBtn:               byId('emoji-btn'),
        emojiPicker:            byId('emoji-picker'),
        messageInput:           byId('message-input'),
        charCounter:            byId('char-counter'),
        sendButton:             byId('send-button'),

        // Toast Container
        toastContainer:         byId('toast-container')
    };

    /* ── State ───────────────────────────────────────────────────────── */

    const state = {
        client: null,
        joinedAs: null,
        pendingName: null,
        subscriptions: [],
        onlineUsers: [],
        /** 'public' or target username */
        activeConversation: 'public',
        /** conversation key -> Array of message objects */
        conversations: {
            public: []
        },
        /** username -> count */
        unreadCounts: {},
        lastMeta: null
    };

    let joinWatchdog = null;

    /* ── Connection Lifecycle ────────────────────────────────────────── */

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
        client.onWebSocketError = () => {
            setStatus('offline', 'Connection error');
            showToast('WebSocket connection error', 'error');
        };
        client.onStompError = (frame) => {
            console.error('STOMP error', frame.headers, frame.body);
            showChatError('The chat connection failed. Please rejoin.');
            showToast('Connection failed: ' + (frame.headers['message'] || 'STOMP error'), 'error');
        };
        return client;
    }

    async function join(username) {
        hideAlert(dom.joinError);
        setJoinLoading(true);

        await teardown();
        state.pendingName = username;

        setStatus('pending', 'Connecting…');
        state.client = buildClient();
        state.client.activate();
    }

    async function leave() {
        await teardown();
        showToast('You have left the chat', 'info');
        showJoinView();
    }

    async function teardown() {
        clearJoinWatchdog();
        setJoinLoading(false);

        const client = state.client;
        state.client = null;
        state.joinedAs = null;
        state.pendingName = null;
        state.subscriptions = [];
        state.onlineUsers = [];
        state.conversations = { public: [] };
        state.unreadCounts = {};
        state.lastMeta = null;

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
            setJoinLoading(false);
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
        const isReconnecting = state.client && state.client.active;
        setStatus('offline', isReconnecting ? 'Reconnecting…' : 'Disconnected');
        if (isReconnecting) {
            setStatus('pending', 'Reconnecting…');
        }
    }

    function subscribeAll() {
        state.subscriptions = [
            state.client.subscribe(DEST.PUBLIC,      (frame) => onPublicMessage(parse(frame))),
            state.client.subscribe(DEST.USERS,       (frame) => onPresence(parse(frame))),
            state.client.subscribe(DEST.PRIVATE_BOX, (frame) => onPrivateMessage(parse(frame))),
            state.client.subscribe(DEST.ERROR_BOX,   (frame) => onError(parse(frame)))
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
        if (!message) return;

        const isOwn = message.sender === state.joinedAs;
        const msgObj = {
            id: message.messageId || String(Date.now()),
            type: 'public',
            sender: message.sender,
            text: message.content,
            time: message.timestamp,
            outgoing: isOwn
        };

        if (!state.conversations.public) {
            state.conversations.public = [];
        }
        state.conversations.public.push(msgObj);

        if (state.activeConversation === 'public') {
            appendMessageToDom(msgObj);
        } else {
            // Update unread badge on Public Chat
            updatePublicUnread(1);
        }
    }

    function onPrivateMessage(message) {
        if (!message) return;

        const outgoing = message.sender === state.joinedAs;
        const otherParty = outgoing ? message.recipient : message.sender;

        const msgObj = {
            id: message.messageId || String(Date.now()),
            type: 'private',
            sender: message.sender,
            recipient: message.recipient,
            text: message.content,
            time: message.timestamp,
            outgoing: outgoing
        };

        if (!state.conversations[otherParty]) {
            state.conversations[otherParty] = [];
        }
        state.conversations[otherParty].push(msgObj);

        if (state.activeConversation === otherParty) {
            appendMessageToDom(msgObj);
        } else {
            // Unread for otherParty
            state.unreadCounts[otherParty] = (state.unreadCounts[otherParty] || 0) + 1;
            renderUsers(state.onlineUsers);
            updatePrivateUnreadTotal();
            if (!outgoing) {
                showToast('Private message from ' + message.sender + ': ' + truncate(message.content, 40), 'info');
            }
        }
    }

    function onPresence(presence) {
        if (!presence) return;

        const users = presence.users || [];
        state.onlineUsers = users;
        renderUsers(users);

        if (presence.event === 'JOIN' || presence.event === 'LEAVE') {
            const isJoin = presence.event === 'JOIN';
            const eventText = presence.username + (isJoin ? ' joined the chat' : ' left the chat');

            const sysMsg = {
                id: 'sys-' + Date.now() + '-' + Math.random(),
                type: 'system',
                text: eventText,
                time: presence.timestamp
            };

            if (!state.conversations.public) {
                state.conversations.public = [];
            }
            state.conversations.public.push(sysMsg);

            if (state.activeConversation === 'public') {
                appendMessageToDom(sysMsg);
            }

            if (state.joinedAs && presence.username !== state.joinedAs) {
                showToast(eventText, isJoin ? 'success' : 'info');
            }
        }

        // Our own join is confirmed by seeing our name in the user list.
        if (!state.joinedAs && state.pendingName && containsIgnoreCase(users, state.pendingName)) {
            state.joinedAs = state.pendingName;
            state.pendingName = null;
            setJoinLoading(false);
            hideAlert(dom.joinError);
            showChatView();
            showToast('Welcome to ChatApp, ' + state.joinedAs + '!', 'success');
        }

        // If currently viewing a user who went offline, update subtitle
        if (state.activeConversation !== 'public') {
            const isStillOnline = containsIgnoreCase(users, state.activeConversation);
            dom.activeConversationSubtitle.textContent = isStillOnline
                ? 'Direct message · Online'
                : 'Direct message · Offline';
        } else {
            dom.activeConversationSubtitle.textContent = users.length + ' online';
        }
    }

    function onError(error) {
        if (!error) return;

        const text = error.message || 'The server rejected the request.';

        if (!state.joinedAs) {
            state.pendingName = null;
            setJoinLoading(false);
            dom.joinError.textContent = text;
            dom.joinError.hidden = false;
            window.setTimeout(() => { void teardown(); }, 0);
            return;
        }
        showChatError(text);
        showToast(text, 'error');
    }

    /* ── Sending ─────────────────────────────────────────────────────── */

    function sendMessage() {
        const text = dom.messageInput.value;
        if (text.trim() === '') return;

        if (!state.client || !state.client.connected || !state.joinedAs) {
            showChatError('You are not connected.');
            showToast('Cannot send message: Not connected', 'warning');
            return;
        }

        if (text.length > MAX_CONTENT) {
            showChatError('Messages must be at most ' + MAX_CONTENT + ' characters.');
            return;
        }

        const isDirect = state.activeConversation !== 'public';
        const recipient = isDirect ? state.activeConversation : dom.recipientSelect.value;
        const destination = recipient ? DEST.PRIVATE : DEST.SEND;
        const body = recipient
            ? { recipient: recipient, content: text }
            : { content: text };

        state.client.publish({ destination: destination, body: JSON.stringify(body) });

        dom.messageInput.value = '';
        updateCharCounter();
        dom.messageInput.focus();
    }

    /* ── Conversation Management ─────────────────────────────────────── */

    function selectConversation(target) {
        state.activeConversation = target;

        // Reset unread for target
        if (target === 'public') {
            dom.publicUnreadBadge.textContent = '0';
            dom.publicUnreadBadge.hidden = true;
        } else {
            if (state.unreadCounts[target]) {
                state.unreadCounts[target] = 0;
                updatePrivateUnreadTotal();
            }
        }

        // Update nav and user list active styling
        dom.navPublicBtn.classList.toggle('is-active', target === 'public');
        dom.navPrivateBtn.classList.toggle('is-active', target !== 'public');

        const userItems = dom.userList.querySelectorAll('.user-item');
        for (const item of userItems) {
            item.classList.toggle('is-selected', item.dataset.username === target);
        }

        // Update chat header
        if (target === 'public') {
            dom.headerAvatar.className = 'header-badge header-badge--group';
            dom.headerAvatar.style.backgroundColor = '#475569';
            dom.headerGroupIcon.hidden = false;
            dom.headerUserInitial.hidden = true;
            dom.activeConversationTitle.textContent = 'Public Chat';
            dom.activeConversationSubtitle.textContent = state.onlineUsers.length + ' online';
            dom.recipientSelect.value = '';
            dom.messageInput.placeholder = 'Type a message…';
        } else {
            const color = getAvatarColor(target);
            dom.headerAvatar.className = 'header-badge';
            dom.headerAvatar.style.backgroundColor = color.bg;
            dom.headerGroupIcon.hidden = true;
            dom.headerUserInitial.hidden = false;
            dom.headerUserInitial.textContent = target.charAt(0).toUpperCase();

            dom.activeConversationTitle.textContent = '@' + target;
            const isOnline = containsIgnoreCase(state.onlineUsers, target);
            dom.activeConversationSubtitle.textContent = isOnline
                ? 'Direct message · Online'
                : 'Direct message · Offline';
            dom.recipientSelect.value = target;
            dom.messageInput.placeholder = 'Message @' + target + '…';
        }

        // Re-render message history for this conversation
        renderActiveConversationMessages();

        // Close mobile drawer if open
        closeMobileSidebar();
    }

    function renderActiveConversationMessages() {
        dom.messages.replaceChildren();
        state.lastMeta = null;

        const list = state.conversations[state.activeConversation] || [];
        if (list.length === 0) {
            renderEmptyState();
            return;
        }

        for (const msg of list) {
            appendMessageToDom(msg);
        }
        dom.messages.scrollTop = dom.messages.scrollHeight;
    }

    function renderEmptyState() {
        const wrap = document.createElement('div');
        wrap.className = 'messages-empty';

        const icon = document.createElement('div');
        icon.className = 'messages-empty__icon';
        icon.textContent = '💬';

        const title = document.createElement('h3');
        title.className = 'messages-empty__title';
        title.textContent = state.activeConversation === 'public'
            ? 'Welcome to Public Chat!'
            : 'Direct Messages with @' + state.activeConversation;

        const desc = document.createElement('p');
        desc.className = 'messages-empty__desc';
        desc.textContent = state.activeConversation === 'public'
            ? 'Say hello to everyone in the room! All messages are visible to online users.'
            : 'This is the start of your private conversation history. Messages are securely routed between you two.';

        wrap.append(icon, title, desc);
        dom.messages.append(wrap);
    }

    function updatePublicUnread(delta) {
        const current = parseInt(dom.publicUnreadBadge.textContent || '0', 10);
        const next = current + delta;
        dom.publicUnreadBadge.textContent = String(next);
        dom.publicUnreadBadge.hidden = next <= 0;
    }

    function updatePrivateUnreadTotal() {
        let total = 0;
        for (const k in state.unreadCounts) {
            total += state.unreadCounts[k];
        }
        dom.privateUnreadBadge.textContent = String(total);
        dom.privateUnreadBadge.hidden = total <= 0;
    }

    /* ── Rendering Messages Safely ───────────────────────────────────── */

    function appendMessageToDom(msg) {
        // Remove empty state if present
        const empty = dom.messages.querySelector('.messages-empty');
        if (empty) {
            empty.remove();
        }

        const isPinned = isScrolledToBottom();

        if (msg.type === 'system') {
            const item = document.createElement('li');
            item.className = 'msg msg--system';

            const badge = document.createElement('div');
            badge.className = 'msg__system-badge';
            badge.textContent = msg.text;

            const time = document.createElement('div');
            time.className = 'msg__system-time';
            time.textContent = formatTime(msg.time);

            item.append(badge, time);
            dom.messages.append(item);
            state.lastMeta = null;
        } else if (msg.outgoing) {
            // Outgoing message (blue bubble on right, with avatar)
            const item = document.createElement('li');
            item.className = 'msg msg--outgoing';

            const isGrouped = state.lastMeta &&
                state.lastMeta.sender === msg.sender &&
                (new Date(msg.time) - new Date(state.lastMeta.time)) < 90000;

            if (isGrouped) {
                item.classList.add('is-grouped');
            }

            const contentWrap = document.createElement('div');
            contentWrap.className = 'msg__content-wrap';

            const bubble = document.createElement('div');
            bubble.className = 'msg__bubble msg__bubble--own';

            const text = document.createElement('span');
            text.className = 'msg__text';
            text.textContent = msg.text;

            const statusLine = document.createElement('div');
            statusLine.className = 'msg__status-line';

            const time = document.createElement('span');
            time.className = 'msg__time msg__time--own';
            time.textContent = formatTime(msg.time);

            const check = document.createElement('span');
            check.className = 'msg__checkmark';
            check.textContent = '✓✓';

            statusLine.append(time, check);
            bubble.append(text, statusLine);
            contentWrap.append(bubble);

            const avatar = createAvatarElement(msg.sender, 'avatar--md');

            item.append(contentWrap, avatar);
            dom.messages.append(item);

            state.lastMeta = { sender: msg.sender, time: msg.time };
        } else {
            // Incoming message (gray bubble on left with avatar & header)
            const item = document.createElement('li');
            item.className = 'msg msg--incoming' + (msg.type === 'private' ? ' msg--private' : '');

            const isGrouped = state.lastMeta &&
                state.lastMeta.sender === msg.sender &&
                (new Date(msg.time) - new Date(state.lastMeta.time)) < 90000;

            if (isGrouped) {
                item.classList.add('is-grouped');
            }

            const avatar = createAvatarElement(msg.sender, 'avatar--md');

            const contentWrap = document.createElement('div');
            contentWrap.className = 'msg__content-wrap';

            const meta = document.createElement('div');
            meta.className = 'msg__meta';

            if (msg.type === 'private') {
                const tag = document.createElement('span');
                tag.className = 'msg__tag';
                tag.textContent = 'Direct';
                meta.append(tag);
            }

            const sender = document.createElement('span');
            sender.className = 'msg__sender';
            sender.textContent = msg.sender;

            const time = document.createElement('span');
            time.className = 'msg__time';
            time.textContent = formatTime(msg.time);

            meta.append(sender, time);

            const bubble = document.createElement('div');
            bubble.className = 'msg__bubble';

            const text = document.createElement('span');
            text.className = 'msg__text';
            text.textContent = msg.text;

            bubble.append(text);
            contentWrap.append(meta, bubble);

            item.append(avatar, contentWrap);
            dom.messages.append(item);

            state.lastMeta = { sender: msg.sender, time: msg.time };
        }

        // Cap DOM growth
        while (dom.messages.childElementCount > MAX_RENDERED) {
            dom.messages.firstElementChild.remove();
        }

        if (isPinned) {
            dom.messages.scrollTop = dom.messages.scrollHeight;
            dom.scrollBottomBtn.hidden = true;
        } else {
            dom.scrollBottomBtn.hidden = false;
        }
    }

    function createAvatarElement(name, sizeClass) {
        const div = document.createElement('div');
        div.className = 'avatar ' + (sizeClass || 'avatar--md');
        const color = getAvatarColor(name);
        div.style.backgroundColor = color.bg;
        div.style.color = color.text;
        div.textContent = name ? name.charAt(0).toUpperCase() : '?';
        div.setAttribute('aria-hidden', 'true');
        return div;
    }

    function renderUsers(users) {
        dom.userCount.textContent = String(users.length);
        dom.userList.replaceChildren();

        const query = (dom.userSearchInput.value || '').trim().toLowerCase();
        let matched = 0;

        for (const name of users) {
            if (query && !name.toLowerCase().includes(query)) {
                continue;
            }
            matched++;

            const isSelf = name === state.joinedAs;
            const isSelected = name === state.activeConversation;

            const item = document.createElement('li');
            item.className = 'user-item' +
                (isSelf ? ' is-self' : '') +
                (isSelected ? ' is-selected' : '');
            item.dataset.username = name;

            const dot = document.createElement('span');
            dot.className = 'user-item__dot';

            const avatar = createAvatarElement(name, 'avatar--sm');

            const nameSpan = document.createElement('span');
            nameSpan.className = 'user-item__name';
            nameSpan.textContent = name;

            item.append(dot, avatar, nameSpan);

            // Unread badge for this user
            const unread = state.unreadCounts[name] || 0;
            if (unread > 0) {
                const unreadBadge = document.createElement('span');
                unreadBadge.className = 'user-item__unread';
                unreadBadge.textContent = String(unread);
                item.append(unreadBadge);
            }

            if (!isSelf) {
                item.addEventListener('click', () => {
                    selectConversation(name);
                });
            }

            dom.userList.append(item);
        }

        if (matched === 0) {
            const empty = document.createElement('li');
            empty.className = 'users-list__empty';
            empty.textContent = query ? 'No users matching "' + query + '"' : 'Nobody online.';
            dom.userList.append(empty);
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

        dom.recipientSelect.value = containsIgnoreCase(users, previous) ? previous : '';
    }

    /* ── UI View Transitions ─────────────────────────────────────────── */

    function showChatView() {
        clearJoinWatchdog();
        dom.joinView.hidden = true;
        dom.chatView.hidden = false;

        dom.identity.textContent = state.joinedAs;
        const color = getAvatarColor(state.joinedAs);
        dom.currentUserAvatar.style.backgroundColor = color.bg;
        dom.currentUserAvatar.textContent = state.joinedAs.charAt(0).toUpperCase();

        selectConversation('public');
        dom.messageInput.focus();
    }

    function showJoinView() {
        dom.chatView.hidden = true;
        dom.joinView.hidden = false;
        dom.identity.textContent = 'Not joined';
        dom.messages.replaceChildren();
        hideAlert(dom.chatError);
        hideAlert(dom.joinError);
        renderUsers([]);
        renderEmptyState();
        dom.usernameInput.focus();
    }

    function setStatus(kind, text) {
        dom.connectionStatus.className = 'chip chip--' + kind;
        dom.connectionText.textContent = text;
        dom.connectionTextSidebar.textContent = text;

        const isOnline = kind === 'online';
        const isOffline = kind === 'offline';

        dom.reconnectButton.hidden = !isOffline;
        dom.sendButton.disabled = !isOnline;

        if (isOffline) {
            dom.messageInput.placeholder = 'Disconnected from server…';
        } else if (state.activeConversation === 'public') {
            dom.messageInput.placeholder = 'Type a message…';
        } else {
            dom.messageInput.placeholder = 'Message @' + state.activeConversation + '…';
        }
    }

    function setJoinLoading(loading) {
        dom.joinButton.disabled = loading;
        dom.joinBtnText.textContent = loading ? 'Connecting…' : 'Connect to Chat';
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

    function showToast(message, type) {
        const toast = document.createElement('div');
        toast.className = 'toast toast--' + (type || 'info');

        const icon = document.createElement('span');
        icon.className = 'toast__icon';
        icon.textContent = type === 'success' ? '●' : (type === 'error' ? '▲' : 'ℹ');

        const text = document.createElement('span');
        text.className = 'toast__text';
        text.textContent = message;

        toast.append(icon, text);
        dom.toastContainer.append(toast);

        window.setTimeout(() => {
            toast.style.transition = 'opacity 0.2s, transform 0.2s';
            toast.style.opacity = '0';
            toast.style.transform = 'translateY(-10px)';
            window.setTimeout(() => toast.remove(), 250);
        }, 3500);
    }

    /* ── Helper Functions ────────────────────────────────────────────── */

    function formatTime(isoTimestamp) {
        const date = isoTimestamp ? new Date(isoTimestamp) : new Date();
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    }

    function isScrolledToBottom() {
        return dom.messages.scrollHeight - dom.messages.scrollTop - dom.messages.clientHeight < 60;
    }

    function containsIgnoreCase(list, value) {
        const needle = String(value).toLowerCase();
        return list.some((entry) => String(entry).toLowerCase() === needle);
    }

    function truncate(str, len) {
        if (!str) return '';
        return str.length > len ? str.slice(0, len) + '…' : str;
    }

    function updateCharCounter() {
        const len = dom.messageInput.value.length;
        dom.charCounter.textContent = len + '/' + MAX_CONTENT;
        dom.charCounter.hidden = len === 0;

        dom.charCounter.classList.toggle('is-warning', len > 1850 && len <= MAX_CONTENT);
        dom.charCounter.classList.toggle('is-limit', len > MAX_CONTENT);
        dom.sendButton.disabled = len > MAX_CONTENT || !state.client || !state.client.connected;
    }

    function closeMobileSidebar() {
        dom.sidebar.classList.remove('is-open');
        dom.sidebarBackdrop.classList.remove('is-open');
    }

    /* ── Theme Management ────────────────────────────────────────────── */

    function initTheme() {
        const saved = localStorage.getItem('chat_theme');
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        const theme = saved || (prefersDark ? 'dark' : 'light');
        document.documentElement.setAttribute('data-theme', theme);
    }

    function toggleTheme() {
        const current = document.documentElement.getAttribute('data-theme') || 'light';
        const next = current === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('chat_theme', next);
    }

    /* ── Health Indicator ────────────────────────────────────────────── */

    async function refreshHealth() {
        try {
            const response = await fetch('/api/health', { headers: { Accept: 'application/json' } });
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            const health = await response.json();
            dom.serverHealth.textContent = 'server: ' + health.status + ' · ' + health.onlineUsers + ' online';
        } catch (error) {
            dom.serverHealth.textContent = 'server: unreachable';
        }
    }

    /* ── Event Listeners ─────────────────────────────────────────────── */

    // Join Form
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

    // Username preview
    dom.usernameInput.addEventListener('input', () => {
        const val = dom.usernameInput.value.trim();
        const color = getAvatarColor(val || '?');
        dom.joinAvatarPreview.style.backgroundColor = color.bg;
        dom.joinAvatarPreview.textContent = val ? val.charAt(0).toUpperCase() : '?';
    });

    // Message Form
    dom.messageForm.addEventListener('submit', (event) => {
        event.preventDefault();
        sendMessage();
    });

    dom.messageInput.addEventListener('input', updateCharCounter);

    dom.messageInput.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            sendMessage();
        }
    });

    // Scroll to bottom button
    dom.scrollBottomBtn.addEventListener('click', () => {
        dom.messages.scrollTop = dom.messages.scrollHeight;
        dom.scrollBottomBtn.hidden = true;
    });

    dom.messages.addEventListener('scroll', () => {
        if (isScrolledToBottom()) {
            dom.scrollBottomBtn.hidden = true;
        }
    });

    // Navigation buttons
    dom.navPublicBtn.addEventListener('click', () => {
        selectConversation('public');
    });

    dom.navPrivateBtn.addEventListener('click', () => {
        // If there's an online user who is not self, select the first one; else notify
        const others = state.onlineUsers.filter(u => u !== state.joinedAs);
        if (others.length > 0) {
            selectConversation(others[0]);
        } else {
            showToast('No other users are currently online for private messaging.', 'info');
        }
    });

    // Search Users
    dom.userSearchInput.addEventListener('input', () => {
        const hasText = dom.userSearchInput.value.length > 0;
        dom.searchClearBtn.hidden = !hasText;
        renderUsers(state.onlineUsers);
    });

    dom.searchClearBtn.addEventListener('click', () => {
        dom.userSearchInput.value = '';
        dom.searchClearBtn.hidden = true;
        renderUsers(state.onlineUsers);
        dom.userSearchInput.focus();
    });

    // Emoji Picker
    dom.emojiBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        dom.emojiPicker.hidden = !dom.emojiPicker.hidden;
    });

    dom.emojiPicker.addEventListener('click', (event) => {
        const target = event.target.closest('.emoji-item');
        if (target && target.dataset.emoji) {
            dom.messageInput.value += target.dataset.emoji;
            updateCharCounter();
            dom.emojiPicker.hidden = true;
            dom.messageInput.focus();
        }
    });

    // Settings Dropdown
    dom.profileSettingsBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        dom.settingsMenu.hidden = !dom.settingsMenu.hidden;
    });

    document.addEventListener('click', (event) => {
        if (!dom.settingsMenu.hidden && !event.target.closest('.profile-actions')) {
            dom.settingsMenu.hidden = true;
        }
        if (!dom.emojiPicker.hidden && !event.target.closest('.composer__actions-left')) {
            dom.emojiPicker.hidden = true;
        }
    });

    // Leave & Reconnect
    dom.leaveButton.addEventListener('click', () => {
        dom.settingsMenu.hidden = true;
        void leave();
    });

    dom.reconnectButton.addEventListener('click', () => {
        if (state.joinedAs) {
            void join(state.joinedAs);
        }
    });

    dom.reconnectMenuBtn.addEventListener('click', () => {
        dom.settingsMenu.hidden = true;
        if (state.joinedAs) {
            void join(state.joinedAs);
        }
    });

    // Theme Toggle
    dom.themeToggleBtn.addEventListener('click', toggleTheme);

    // Mobile Sidebar Drawer
    dom.mobileMenuBtn.addEventListener('click', () => {
        dom.sidebar.classList.add('is-open');
        dom.sidebarBackdrop.classList.add('is-open');
    });

    dom.sidebarCloseBtn.addEventListener('click', closeMobileSidebar);
    dom.sidebarBackdrop.addEventListener('click', closeMobileSidebar);

    window.addEventListener('beforeunload', () => {
        if (state.client) {
            state.client.deactivate({ force: true });
        }
    });

    /* ── Initial Boot ────────────────────────────────────────────────── */

    initTheme();
    setStatus('offline', 'Disconnected');
    showJoinView();
    void refreshHealth();
    window.setInterval(refreshHealth, 30000);
})();
