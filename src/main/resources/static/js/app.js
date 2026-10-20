/**
 * ChatSphere browser client: REST + native STOMP over WebSocket.
 * User-generated content is rendered with textContent only.
 */
(function () {
    'use strict';

    const API = '/api';
    const DEST = Object.freeze({
        JOIN: '/app/chat.join',
        SEND: '/app/chat.send',
        PRIVATE: '/app/chat.private',
        PUBLIC: '/topic/public',
        USERS: '/topic/users',
        PRIVATE_BOX: '/user/queue/private',
        ERROR_BOX: '/user/queue/errors'
    });

    const state = {
        user: null,
        conversations: [],
        friends: [],
        incoming: [],
        outgoing: [],
        messages: new Map(),
        currentConversation: null,
        filter: 'all',
        view: 'chats',
        subscriptions: new Map(),
        stomp: null,
        theme: localStorage.getItem('chatsphere-theme') || 'light'
    };

    const $ = (id) => document.getElementById(id);

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function setAvatar(node, name, url) {
        node.textContent = (name || '?').charAt(0).toUpperCase();
        if (url) {
            node.style.backgroundImage = 'url("' + url + '")';
            node.textContent = '';
        } else {
            node.style.backgroundImage = 'none';
        }
    }

    function toast(message) {
        const item = el('div', 'toast', message);
        $('toast-container').appendChild(item);
        setTimeout(() => item.remove(), 3200);
    }

    function showError(node, message) {
        node.hidden = !message;
        node.textContent = message || '';
    }

    async function request(path, options) {
        const res = await fetch(API + path, Object.assign({
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' }
        }, options));
        if (res.status === 204) return null;
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            throw new Error(data.message || data.error || 'Request failed');
        }
        return data;
    }

    function applyTheme(theme) {
        state.theme = theme === 'dark' ? 'dark' : 'light';
        document.documentElement.setAttribute('data-theme', state.theme);
        localStorage.setItem('chatsphere-theme', state.theme);
        $('theme-light').classList.toggle('is-active', state.theme === 'light');
        $('theme-dark').classList.toggle('is-active', state.theme === 'dark');
    }

    function firstName(value) {
        return (value || 'there').split(' ')[0];
    }

    function formatTime(value) {
        if (!value) return '';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        return date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    }

    function isGroup(conv) {
        return conv && (conv.isGroup === true || conv.type === 'GROUP');
    }

    function convTitle(conv) {
        if (!conv) return 'Chat';
        return conv.title || conv.otherUsername || 'Conversation';
    }

    function updateProfileChrome() {
        const user = state.user;
        if (!user) return;
        setAvatar($('rail-avatar'), user.displayName, user.avatarUrl);
        setAvatar($('top-avatar'), user.displayName, user.avatarUrl);
        $('rail-name').textContent = user.displayName;
        $('rail-username').textContent = '@' + user.username;
        $('top-name').textContent = user.displayName;
        $('welcome-title').textContent = 'Welcome back, ' + firstName(user.displayName) + '!';
        $('settings-display').textContent = user.displayName;
        $('settings-handle').textContent = '@' + user.username;
        $('profile-displayname').value = user.displayName || '';
        $('profile-username').value = user.username || '';
        $('profile-email').value = user.email || '';
        $('profile-bio').value = user.bio || '';
        setAvatar($('profile-avatar-preview'), user.displayName, user.avatarUrl);
    }

    function showAuth(which) {
        $('login-view').hidden = which !== 'login';
        $('register-view').hidden = which !== 'register';
        $('app-shell').hidden = true;
    }

    function showApp() {
        $('login-view').hidden = true;
        $('register-view').hidden = true;
        $('app-shell').hidden = false;
        updateProfileChrome();
        openView('chats');
    }

    function setRail(view) {
        document.querySelectorAll('.rail__item').forEach((btn) => {
            btn.classList.toggle('is-active', btn.dataset.view === view);
        });
    }

    function openView(view, options) {
        state.view = view;
        setRail(view === 'groups' ? 'groups' : view);
        $('dashboard-view').hidden = true;
        $('chat-view').hidden = true;
        $('friends-view').hidden = true;
        $('requests-view').hidden = true;
        $('group-view').hidden = true;
        $('settings-view').hidden = true;
        $('list-tabs').hidden = view !== 'chats' && view !== 'groups';
        $('conversation-list').hidden = view === 'friends';
        $('friends-side-list').hidden = view !== 'friends';
        $('app-shell').classList.remove('show-chat', 'show-page');

        if (view === 'chats') {
            state.filter = 'all';
            document.querySelectorAll('#list-tabs .tab').forEach((tab) => {
                tab.classList.toggle('is-active', tab.dataset.filter === 'all');
            });
            renderConversations();
            if (options && options.conversationId) {
                openConversation(options.conversationId);
            } else {
                $('dashboard-view').hidden = false;
            }
        } else if (view === 'groups') {
            state.filter = 'groups';
            document.querySelectorAll('#list-tabs .tab').forEach((tab) => {
                tab.classList.toggle('is-active', tab.dataset.filter === 'groups');
            });
            renderConversations();
            $('dashboard-view').hidden = false;
        } else if (view === 'friends') {
            $('friends-view').hidden = false;
            $('app-shell').classList.add('show-page');
            renderFriends();
            renderFriendsSide();
        } else if (view === 'requests') {
            $('requests-view').hidden = false;
            $('app-shell').classList.add('show-page');
            loadFriends();
        } else if (view === 'settings') {
            $('settings-view').hidden = false;
            $('app-shell').classList.add('show-page');
            loadSettings();
        } else if (view === 'group') {
            $('group-view').hidden = false;
            $('app-shell').classList.add('show-page');
        }
    }

    function renderConversations() {
        const list = $('conversation-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        const query = ($('conv-search').value || '').toLowerCase();
        const items = state.conversations.filter((conv) => {
            const group = isGroup(conv);
            if (state.filter === 'private' && group) return false;
            if (state.filter === 'groups' && !group) return false;
            const title = convTitle(conv).toLowerCase();
            const preview = (conv.lastMessageText || '').toLowerCase();
            return !query || title.includes(query) || preview.includes(query);
        });
        if (!items.length) {
            list.appendChild(el('li', 'empty', 'No conversations yet'));
            return;
        }
        items.forEach((conv) => {
            const row = el('button', 'person');
            row.type = 'button';
            if (state.currentConversation && state.currentConversation.id === conv.id) {
                row.classList.add('is-active');
            }
            const avatar = el('div', 'avatar');
            setAvatar(avatar, convTitle(conv), conv.avatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', convTitle(conv)));
            mid.appendChild(el('span', '', conv.lastMessageText || 'No messages yet'));
            row.appendChild(avatar);
            row.appendChild(mid);
            const right = el('div');
            right.appendChild(el('time', '', formatTime(conv.lastMessageAt)));
            if (conv.unreadCount > 0) right.appendChild(el('em', 'unread', String(conv.unreadCount)));
            row.appendChild(right);
            row.addEventListener('click', () => openConversation(conv.id));
            list.appendChild(row);
        });
    }

    function renderFriendsSide() {
        const list = $('friends-side-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!state.friends.length) {
            list.appendChild(el('li', 'empty', 'No friends yet'));
            return;
        }
        state.friends.forEach((friend) => {
            const row = el('button', 'person');
            row.type = 'button';
            const avatar = el('div', 'avatar');
            setAvatar(avatar, friend.displayName, friend.avatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', friend.displayName));
            mid.appendChild(el('span', '', friend.online ? 'Online' : '@' + friend.username));
            row.appendChild(avatar);
            row.appendChild(mid);
            row.addEventListener('click', () => startDirect(friend.id));
            list.appendChild(row);
        });
    }

    function renderFriends() {
        fillPeopleList($('friends-list'), state.friends, (friend) => {
            const message = el('button', 'pill pill-ok', 'Message');
            message.type = 'button';
            message.addEventListener('click', () => startDirect(friend.id));
            return message;
        });
        fillPeopleList($('requests-friends-list'), state.friends, (friend) => {
            const message = el('button', 'pill pill-ok', 'Message');
            message.type = 'button';
            message.addEventListener('click', () => startDirect(friend.id));
            return message;
        });
    }

    function fillPeopleList(list, people, actionFactory) {
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!people.length) {
            list.appendChild(el('div', 'empty', 'Nothing here yet'));
            return;
        }
        people.forEach((person) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, person.displayName || person.senderDisplayName || person.receiverDisplayName, person.avatarUrl || person.senderAvatarUrl || person.receiverAvatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', person.displayName || person.senderDisplayName || person.receiverDisplayName || 'User'));
            mid.appendChild(el('span', 'muted', person.username ? '@' + person.username : (person.senderUsername ? '@' + person.senderUsername : '@' + (person.receiverUsername || ''))));
            row.appendChild(avatar);
            row.appendChild(mid);
            if (actionFactory) row.appendChild(actionFactory(person));
            list.appendChild(row);
        });
    }

    function renderRequests() {
        fillPeopleList($('incoming-requests-list'), state.incoming, (req) => {
            const wrap = el('div', 'row-gap');
            wrap.style.marginTop = '0';
            const accept = el('button', 'pill pill-ok', 'Accept');
            const reject = el('button', 'pill pill-no', 'Reject');
            accept.type = 'button';
            reject.type = 'button';
            accept.addEventListener('click', () => actOnRequest(req.id, 'accept'));
            reject.addEventListener('click', () => actOnRequest(req.id, 'reject'));
            wrap.appendChild(accept);
            wrap.appendChild(reject);
            return wrap;
        });
        fillPeopleList($('outgoing-requests-list'), state.outgoing, (req) => {
            const cancel = el('button', 'pill pill-mute', 'Cancel');
            cancel.type = 'button';
            cancel.addEventListener('click', () => actOnRequest(req.id, 'cancel'));
            return cancel;
        });
        const badge = $('requests-badge');
        badge.hidden = state.incoming.length === 0;
        badge.textContent = String(state.incoming.length);
    }

    async function actOnRequest(id, action) {
        try {
            if (action === 'cancel') {
                await request('/friends/requests/' + id, { method: 'DELETE' });
            } else {
                await request('/friends/requests/' + id + '/' + action, { method: 'POST', body: '{}' });
            }
            await loadFriends();
            toast(action === 'accept' ? 'Friend request accepted' : 'Updated');
        } catch (err) {
            toast(err.message);
        }
    }

    function renderMessages() {
        const thread = $('chat-messages');
        while (thread.firstChild) thread.removeChild(thread.firstChild);
        const conv = state.currentConversation;
        if (!conv) return;
        const items = state.messages.get(conv.id) || [];
        items.forEach((msg) => {
            const mine = state.user && msg.senderId === state.user.id;
            const bubble = el('div', 'bubble ' + (mine ? 'bubble--out' : 'bubble--in'));
            if (!mine && isGroup(conv)) {
                bubble.appendChild(el('small', '', msg.senderUsername || 'User'));
            }
            bubble.appendChild(el('div', '', msg.deleted ? 'This message was deleted.' : msg.content));
            const meta = formatTime(msg.createdAt) + (msg.edited ? ' · edited' : '');
            bubble.appendChild(el('small', '', meta));
            thread.appendChild(bubble);
        });
        thread.scrollTop = thread.scrollHeight;
    }

    function upsertMessage(msg) {
        if (!msg || !msg.conversationId) return;
        const list = state.messages.get(msg.conversationId) || [];
        const idx = list.findIndex((item) => item.id === msg.id);
        if (idx >= 0) list[idx] = msg;
        else list.push(msg);
        state.messages.set(msg.conversationId, list);
        const conv = state.conversations.find((item) => item.id === msg.conversationId);
        if (conv) {
            conv.lastMessageText = msg.content;
            conv.lastMessageAt = msg.createdAt;
        }
        if (state.currentConversation && state.currentConversation.id === msg.conversationId) {
            renderMessages();
        }
        renderConversations();
    }

    async function openConversation(id) {
        const conv = state.conversations.find((item) => item.id === id);
        if (!conv) return;
        state.currentConversation = conv;
        $('dashboard-view').hidden = true;
        $('friends-view').hidden = true;
        $('requests-view').hidden = true;
        $('settings-view').hidden = true;
        $('group-view').hidden = true;
        $('chat-view').hidden = false;
        $('app-shell').classList.add('show-chat');
        $('app-shell').classList.remove('show-page');
        setAvatar($('chat-avatar'), convTitle(conv), conv.avatarUrl);
        $('chat-title').textContent = convTitle(conv);
        $('chat-subtitle').textContent = isGroup(conv) ? 'Group chat' : 'Private chat';
        renderConversations();
        subscribeConversation(id);
        try {
            const messages = await request('/conversations/' + id + '/messages?size=50');
            state.messages.set(id, messages);
            renderMessages();
        } catch (err) {
            toast(err.message);
        }
    }

    async function startDirect(userId) {
        try {
            const conv = await request('/conversations/direct', {
                method: 'POST',
                body: JSON.stringify({ recipientId: userId })
            });
            await loadConversations();
            openView('chats', { conversationId: conv.id });
        } catch (err) {
            toast(err.message);
        }
    }

    async function loadConversations() {
        state.conversations = await request('/conversations');
        renderConversations();
    }

    async function loadFriends() {
        const [friends, incoming, outgoing] = await Promise.all([
            request('/friends'),
            request('/friends/requests/incoming'),
            request('/friends/requests/outgoing')
        ]);
        state.friends = friends;
        state.incoming = incoming;
        state.outgoing = outgoing;
        renderFriends();
        renderFriendsSide();
        renderRequests();
    }

    async function loadSettings() {
        try {
            const settings = await request('/settings');
            applyTheme(settings.theme || state.theme);
            $('pref-notifications').checked = settings.notificationsEnabled;
            $('pref-sound').checked = settings.soundEnabled;
            $('pref-online').checked = settings.onlineVisibility;
        } catch (err) {
            applyTheme(state.theme);
        }
    }

    async function loadInitial() {
        await Promise.all([loadConversations(), loadFriends(), loadSettings()]);
    }

    function closeModal() {
        $('modal').hidden = true;
        const body = $('modal-body');
        const actions = $('modal-actions');
        while (body.firstChild) body.removeChild(body.firstChild);
        while (actions.firstChild) actions.removeChild(actions.firstChild);
    }

    function openModal(title) {
        $('modal-title').textContent = title;
        const body = $('modal-body');
        const actions = $('modal-actions');
        while (body.firstChild) body.removeChild(body.firstChild);
        while (actions.firstChild) actions.removeChild(actions.firstChild);
        $('modal').hidden = false;
        return { body, actions };
    }

    function openNewPrivateModal() {
        const { body, actions } = openModal('Start a private chat');
        const input = el('input');
        input.placeholder = 'Search friends by name';
        const results = el('div', 'card-list');
        body.appendChild(input);
        body.appendChild(results);
        function draw(people) {
            while (results.firstChild) results.removeChild(results.firstChild);
            people.forEach((person) => {
                const row = el('button', 'card-row');
                row.type = 'button';
                const avatar = el('div', 'avatar');
                setAvatar(avatar, person.displayName, person.avatarUrl);
                const mid = el('div');
                mid.appendChild(el('strong', '', person.displayName));
                mid.appendChild(el('span', 'muted', '@' + person.username));
                row.appendChild(avatar);
                row.appendChild(mid);
                row.addEventListener('click', () => {
                    closeModal();
                    startDirect(person.id);
                });
                results.appendChild(row);
            });
        }
        draw(state.friends);
        input.addEventListener('input', () => {
            const q = input.value.toLowerCase();
            draw(state.friends.filter((f) => (f.displayName + f.username).toLowerCase().includes(q)));
        });
        const cancel = el('button', 'btn btn-ghost', 'Cancel');
        cancel.type = 'button';
        cancel.addEventListener('click', closeModal);
        actions.appendChild(cancel);
    }

    function openNewGroupModal() {
        const { body, actions } = openModal('Create a group');
        const title = el('input');
        title.placeholder = 'Group name';
        const desc = el('textarea');
        desc.placeholder = 'Description';
        desc.rows = 3;
        const picks = el('div', 'card-list');
        const selected = new Set();
        state.friends.forEach((friend) => {
            const row = el('label', 'card-row');
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.addEventListener('change', () => {
                if (box.checked) selected.add(friend.id);
                else selected.delete(friend.id);
            });
            const mid = el('div');
            mid.appendChild(el('strong', '', friend.displayName));
            mid.appendChild(el('span', 'muted', '@' + friend.username));
            row.appendChild(box);
            row.appendChild(mid);
            picks.appendChild(row);
        });
        body.appendChild(title);
        body.appendChild(desc);
        body.appendChild(el('p', 'muted', 'Add members'));
        body.appendChild(picks);
        const create = el('button', 'btn btn-primary', 'Create group');
        create.type = 'button';
        create.addEventListener('click', async () => {
            try {
                const conv = await request('/conversations/group', {
                    method: 'POST',
                    body: JSON.stringify({
                        title: title.value,
                        description: desc.value,
                        memberIds: Array.from(selected)
                    })
                });
                closeModal();
                await loadConversations();
                openView('chats', { conversationId: conv.id });
            } catch (err) {
                toast(err.message);
            }
        });
        actions.appendChild(create);
    }

    async function openGroupManage() {
        const conv = state.currentConversation;
        if (!conv || !isGroup(conv)) {
            toast('Open a group chat to manage it');
            return;
        }
        try {
            const details = await request('/conversations/' + conv.id);
            const members = await request('/conversations/' + conv.id + '/members');
            openView('group');
            $('group-manage-title').textContent = details.title || convTitle(conv);
            $('group-manage-meta').textContent = members.length + ' members · Group';
            $('group-title-input').value = details.title || '';
            $('group-desc-input').value = details.description || '';
            const list = $('group-members-list');
            while (list.firstChild) list.removeChild(list.firstChild);
            members.forEach((member) => {
                const row = el('div', 'card-row');
                const avatar = el('div', 'avatar');
                setAvatar(avatar, member.displayName, member.avatarUrl);
                const mid = el('div');
                mid.appendChild(el('strong', '', member.displayName));
                mid.appendChild(el('span', 'muted', member.role + (member.online ? ' · Online' : '')));
                row.appendChild(avatar);
                row.appendChild(mid);
                if (member.userId !== state.user.id) {
                    const remove = el('button', 'pill pill-no', 'Remove');
                    remove.type = 'button';
                    remove.addEventListener('click', async () => {
                        try {
                            await request('/conversations/' + conv.id + '/members/' + member.userId, { method: 'DELETE' });
                            openGroupManage();
                        } catch (err) {
                            toast(err.message);
                        }
                    });
                    row.appendChild(remove);
                }
                list.appendChild(row);
            });
        } catch (err) {
            toast(err.message);
        }
    }

    function openAddMemberModal() {
        const conv = state.currentConversation;
        const { body, actions } = openModal('Add members');
        const results = el('div', 'card-list');
        state.friends.forEach((friend) => {
            const row = el('button', 'card-row');
            row.type = 'button';
            row.appendChild(el('strong', '', friend.displayName));
            row.addEventListener('click', async () => {
                try {
                    await request('/conversations/' + conv.id + '/members', {
                        method: 'POST',
                        body: JSON.stringify({ userId: friend.id })
                    });
                    closeModal();
                    openGroupManage();
                } catch (err) {
                    toast(err.message);
                }
            });
            results.appendChild(row);
        });
        body.appendChild(results);
        const cancel = el('button', 'btn btn-ghost', 'Cancel');
        cancel.type = 'button';
        cancel.addEventListener('click', closeModal);
        actions.appendChild(cancel);
    }

    function createStompClient() {
        let socket = null;
        let connected = false;
        let counter = 0;
        const handlers = {};

        function frame(command, headers, body) {
            let out = command + '\n';
            Object.keys(headers || {}).forEach((key) => {
                out += key + ':' + headers[key] + '\n';
            });
            out += '\n' + (body || '') + '\0';
            socket.send(out);
        }

        function parse(raw) {
            const chunks = raw.split('\0');
            chunks.forEach((chunk) => {
                if (!chunk || chunk === '\n') return;
                const split = chunk.indexOf('\n\n');
                const head = split >= 0 ? chunk.slice(0, split) : chunk;
                const body = split >= 0 ? chunk.slice(split + 2) : '';
                const lines = head.split('\n');
                const command = lines.shift();
                const headers = {};
                lines.forEach((line) => {
                    const idx = line.indexOf(':');
                    if (idx > 0) headers[line.slice(0, idx)] = line.slice(idx + 1);
                });
                if (command === 'CONNECTED') {
                    connected = true;
                    if (handlers.connect) handlers.connect();
                } else if (command === 'MESSAGE') {
                    const dest = headers.destination;
                    const cb = handlers[dest];
                    if (cb) {
                        try { cb(JSON.parse(body)); } catch (e) { cb(body); }
                    }
                } else if (command === 'ERROR' && handlers.error) {
                    handlers.error(body);
                }
            });
        }

        return {
            connect(onConnect, onError) {
                handlers.connect = onConnect;
                handlers.error = onError;
                const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                socket = new WebSocket(proto + '//' + location.host + '/ws');
                socket.onopen = () => frame('CONNECT', {
                    'accept-version': '1.1,1.2',
                    'heart-beat': '10000,10000'
                });
                socket.onmessage = (event) => {
                    const text = typeof event.data === 'string' ? event.data : new TextDecoder().decode(event.data);
                    parse(text);
                };
                socket.onerror = () => onError && onError('WebSocket error');
                socket.onclose = () => { connected = false; };
            },
            subscribe(destination, cb) {
                handlers[destination] = cb;
                frame('SUBSCRIBE', { id: 'sub-' + (++counter), destination: destination });
            },
            send(destination, body) {
                if (!connected) return;
                frame('SEND', {
                    destination: destination,
                    'content-type': 'application/json'
                }, JSON.stringify(body));
            },
            disconnect() {
                if (socket && socket.readyState === WebSocket.OPEN) {
                    frame('DISCONNECT', {});
                    socket.close();
                }
                connected = false;
            }
        };
    }

    function subscribeConversation(id) {
        if (!state.stomp || state.subscriptions.has(id)) return;
        const dest = '/topic/conversations/' + id;
        state.stomp.subscribe(dest, (frame) => {
            if (!frame) return;
            if (frame.event === 'NEW_MESSAGE' || frame.event === 'MESSAGE_EDITED') {
                upsertMessage(frame.data);
            }
        });
        state.subscriptions.set(id, dest);
    }

    function connectWebSocket() {
        if (state.stomp) state.stomp.disconnect();
        state.subscriptions.clear();
        const client = createStompClient();
        state.stomp = client;
        client.connect(() => {
            client.subscribe(DEST.PUBLIC, () => {});
            client.subscribe(DEST.USERS, () => {});
            client.subscribe(DEST.PRIVATE_BOX, () => {});
            client.subscribe(DEST.ERROR_BOX, (err) => {
                if (err && err.message) toast(err.message);
            });
            if (state.user) {
                client.send(DEST.JOIN, { username: state.user.username });
            }
            state.conversations.forEach((conv) => subscribeConversation(conv.id));
        }, () => {
            setTimeout(connectWebSocket, 4000);
        });
    }

    function setupAuth() {
        $('show-register-btn').addEventListener('click', () => showAuth('register'));
        $('show-login-btn').addEventListener('click', () => showAuth('login'));
        $('login-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await request('/auth/login', {
                    method: 'POST',
                    body: JSON.stringify({
                        email: $('login-email').value,
                        password: $('login-password').value
                    })
                });
                await bootAuthenticated();
            } catch (err) {
                showError($('login-error'), err.message);
            }
        });
        $('register-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await request('/auth/register', {
                    method: 'POST',
                    body: JSON.stringify({
                        username: $('reg-username').value,
                        displayName: $('reg-displayname').value,
                        email: $('reg-email').value,
                        password: $('reg-password').value
                    })
                });
                await bootAuthenticated();
            } catch (err) {
                showError($('register-error'), err.message);
            }
        });
    }

    function setupNavigation() {
        document.querySelectorAll('.rail__item').forEach((btn) => {
            btn.addEventListener('click', () => openView(btn.dataset.view));
        });
        $('card-private').addEventListener('click', openNewPrivateModal);
        $('card-group').addEventListener('click', openNewGroupModal);
        $('card-requests').addEventListener('click', () => openView('requests'));
        $('card-settings').addEventListener('click', () => openView('settings'));
        $('chat-back-btn').addEventListener('click', () => {
            state.currentConversation = null;
            $('app-shell').classList.remove('show-chat');
            openView(state.filter === 'groups' ? 'groups' : 'chats');
        });
        $('group-back-btn').addEventListener('click', () => openView('chats', {
            conversationId: state.currentConversation && state.currentConversation.id
        }));
        $('chat-info-btn').addEventListener('click', () => {
            if (state.currentConversation && isGroup(state.currentConversation)) openGroupManage();
            else toast('Private conversation');
        });
        document.querySelectorAll('#list-tabs .tab').forEach((tab) => {
            tab.addEventListener('click', () => {
                state.filter = tab.dataset.filter;
                document.querySelectorAll('#list-tabs .tab').forEach((item) => item.classList.toggle('is-active', item === tab));
                renderConversations();
            });
        });
        document.querySelectorAll('#requests-view .tab').forEach((tab) => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('#requests-view .tab').forEach((item) => item.classList.toggle('is-active', item === tab));
                $('incoming-pane').hidden = tab.dataset.reqtab !== 'incoming';
                $('outgoing-pane').hidden = tab.dataset.reqtab !== 'outgoing';
                $('friends-pane').hidden = tab.dataset.reqtab !== 'friends';
            });
        });
        $('conv-search').addEventListener('input', renderConversations);
        $('user-search-btn').addEventListener('click', searchUsers);
        $('user-search-input').addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
                event.preventDefault();
                searchUsers();
            }
        });
        $('modal-close').addEventListener('click', closeModal);
        $('modal-backdrop').addEventListener('click', closeModal);
        $('add-member-btn').addEventListener('click', openAddMemberModal);
        $('group-save-btn').addEventListener('click', async () => {
            try {
                await request('/conversations/' + state.currentConversation.id, {
                    method: 'PUT',
                    body: JSON.stringify({
                        title: $('group-title-input').value,
                        description: $('group-desc-input').value
                    })
                });
                await loadConversations();
                toast('Group updated');
            } catch (err) {
                toast(err.message);
            }
        });
    }

    async function searchUsers() {
        const query = $('user-search-input').value.trim();
        const list = $('search-results-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        try {
            const page = await request('/users/search?query=' + encodeURIComponent(query));
            const content = page.content || page || [];
            content.forEach((person) => {
                if (state.user && person.id === state.user.id) return;
                const row = el('div', 'card-row');
                const avatar = el('div', 'avatar');
                setAvatar(avatar, person.displayName, person.avatarUrl);
                const mid = el('div');
                mid.appendChild(el('strong', '', person.displayName));
                mid.appendChild(el('span', 'muted', '@' + person.username));
                const add = el('button', 'pill pill-ok', 'Add friend');
                add.type = 'button';
                add.addEventListener('click', async () => {
                    try {
                        await request('/friends/requests', {
                            method: 'POST',
                            body: JSON.stringify({ userId: person.id, username: person.username })
                        });
                        toast('Friend request sent');
                        await loadFriends();
                    } catch (err) {
                        toast(err.message);
                    }
                });
                row.appendChild(avatar);
                row.appendChild(mid);
                row.appendChild(add);
                list.appendChild(row);
            });
        } catch (err) {
            toast(err.message);
        }
    }

    function setupChat() {
        $('message-send-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            const conv = state.currentConversation;
            const input = $('message-content-input');
            const content = input.value.trim();
            if (!conv || !content) return;
            input.value = '';
            try {
                const msg = await request('/conversations/' + conv.id + '/messages', {
                    method: 'POST',
                    body: JSON.stringify({ content: content })
                });
                upsertMessage(msg);
            } catch (err) {
                toast(err.message);
            }
        });
    }

    function setupSettings() {
        $('theme-light').addEventListener('click', () => applyTheme('light'));
        $('theme-dark').addEventListener('click', () => applyTheme('dark'));
        $('save-prefs-btn').addEventListener('click', async () => {
            try {
                await request('/settings', {
                    method: 'PUT',
                    body: JSON.stringify({
                        theme: state.theme,
                        notificationsEnabled: $('pref-notifications').checked,
                        soundEnabled: $('pref-sound').checked,
                        onlineVisibility: $('pref-online').checked
                    })
                });
                toast('Preferences saved');
            } catch (err) {
                toast(err.message);
            }
        });
        $('profile-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                state.user = await request('/users/profile', {
                    method: 'PUT',
                    body: JSON.stringify({
                        displayName: $('profile-displayname').value,
                        bio: $('profile-bio').value,
                        avatarUrl: state.user.avatarUrl
                    })
                });
                updateProfileChrome();
                showError($('profile-error'), '');
                toast('Profile saved');
            } catch (err) {
                showError($('profile-error'), err.message);
            }
        });
        $('avatar-upload-btn').addEventListener('click', () => $('avatar-file-input').click());
        $('avatar-file-input').addEventListener('change', async () => {
            const file = $('avatar-file-input').files[0];
            if (!file) return;
            const data = new FormData();
            data.append('file', file);
            try {
                const res = await fetch(API + '/uploads/avatar', { method: 'POST', credentials: 'include', body: data });
                const payload = await res.json();
                if (!res.ok) throw new Error(payload.message || 'Upload failed');
                state.user = await request('/users/profile', {
                    method: 'PUT',
                    body: JSON.stringify({
                        displayName: state.user.displayName,
                        bio: state.user.bio,
                        avatarUrl: payload.url
                    })
                });
                updateProfileChrome();
            } catch (err) {
                toast(err.message);
            }
        });
        $('avatar-remove-btn').addEventListener('click', async () => {
            state.user = await request('/users/profile', {
                method: 'PUT',
                body: JSON.stringify({
                    displayName: state.user.displayName,
                    bio: state.user.bio,
                    avatarUrl: ''
                })
            });
            updateProfileChrome();
        });
        $('logout-btn').addEventListener('click', async () => {
            try { await request('/auth/logout', { method: 'POST', body: '{}' }); } catch (e) {}
            if (state.stomp) state.stomp.disconnect();
            state.user = null;
            showAuth('login');
        });
    }

    async function bootAuthenticated() {
        const status = await request('/auth/status');
        if (!status.authenticated) {
            showAuth('login');
            return;
        }
        state.user = status.user;
        showApp();
        await loadInitial();
        connectWebSocket();
    }

    async function init() {
        applyTheme(state.theme);
        setupAuth();
        setupNavigation();
        setupChat();
        setupSettings();
        try {
            await bootAuthenticated();
        } catch (e) {
            showAuth('login');
        }
    }

    document.addEventListener('DOMContentLoaded', init);
})();
