/**
 * ChatSphere browser client: REST + native STOMP over WebSocket.
 * User-generated content is rendered with textContent only.
 */
(function () {
    'use strict';

    const API = '/api';
    const DEST = Object.freeze({
        JOIN: '/app/chat.join',
        USERS: '/topic/users',
        ERROR_BOX: '/user/queue/errors'
    });

    const REACTION_EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '🔥', '🎉', '👀'];

    const state = {
        user: null,
        conversations: null,
        messagesLoading: false,
        friends: [],
        incoming: [],
        outgoing: [],
        messages: new Map(),
        currentConversation: null,
        filter: 'all',
        view: 'chats',
        subscriptions: new Map(),
        stomp: null,
        theme: localStorage.getItem('chatsphere-theme') || 'light',
        discoverGroups: [],
        groupJoinRequests: [],
        groupInvitations: [],
        groupMembers: [],
        groupBlocked: [],
        directory: [],
        replyTo: null,
        typingPeers: new Map(),
        pinned: [],
        search: { open: false, query: '', results: null, conversationId: null, timer: null },
        pages: {}
    };

    const $ = (id) => document.getElementById(id);

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    async function withBusy(button, work) {
        if (!button) return work();
        button.disabled = true;
        button.classList.add('is-busy');
        try {
            return await work();
        } finally {
            button.disabled = false;
            button.classList.remove('is-busy');
        }
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
        const now = new Date();
        const sameDay = date.toDateString() === now.toDateString();
        const yesterday = new Date(now);
        yesterday.setDate(now.getDate() - 1);
        if (sameDay) return date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
        if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
        return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
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
        $('discover-view').hidden = true;
        $('group-view').hidden = true;
        $('settings-view').hidden = true;
        $('list-tabs').hidden = view !== 'chats' && view !== 'groups';
        $('conversation-list').hidden = view === 'friends';
        $('friends-side-list').hidden = view !== 'friends';
        $('app-shell').classList.remove('show-chat', 'show-page', 'is-wide');
        if (view === 'friends' || view === 'requests' || view === 'settings' || view === 'group') {
            $('app-shell').classList.add('is-wide');
        }

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
            searchUsers();
        } else if (view === 'requests') {
            $('requests-view').hidden = false;
            $('app-shell').classList.add('show-page');
            loadFriends();
        } else if (view === 'discover') {
            $('discover-view').hidden = false;
            $('app-shell').classList.add('show-page');
            loadDiscoverGroups();
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
        if (state.conversations == null) {
            list.setAttribute('aria-busy', 'true');
            list.appendChild(el('li', 'empty loading-row', 'Loading conversations\u2026'));
            return;
        }
        list.removeAttribute('aria-busy');
        const items = state.conversations.filter((conv) => {
            const group = isGroup(conv);
            if (state.filter === 'private' && group) return false;
            if (state.filter === 'groups' && !group) return false;
            const title = convTitle(conv).toLowerCase();
            const preview = (conv.lastMessageText || '').toLowerCase();
            return !query || title.includes(query) || preview.includes(query);
        });
        if (!items.length) {
            const filtered = query || state.filter !== 'all';
            list.appendChild(el('li', 'empty', filtered
                ? 'No conversations match your search'
                : 'No conversations yet \u2014 start one from Friends'));
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
            if (person.message) {
                mid.appendChild(el('span', 'request-message', '"' + person.message + '"'));
            }
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
        fillPeopleList($('outgoing-requests-list'), state.outgoing.map((req) => ({
            ...req,
            displayName: req.receiverDisplayName,
            username: req.receiverUsername,
            avatarUrl: req.receiverAvatarUrl
        })), (req) => {
            const cancel = el('button', 'pill pill-mute', 'Cancel');
            cancel.type = 'button';
            cancel.addEventListener('click', () => actOnRequest(req.id, 'cancel'));
            return cancel;
        });
        const badge = $('requests-badge');
        const totalRequests = state.incoming.length + state.groupInvitations.length;
        badge.hidden = totalRequests === 0;
        badge.textContent = String(totalRequests);
        $('incoming-count').textContent = '(' + state.incoming.length + ')';
        $('outgoing-count').textContent = '(' + state.outgoing.length + ')';
        $('friends-count').textContent = '(' + state.friends.length + ')';
        $('group-invites-count').textContent = '(' + state.groupInvitations.length + ')';
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
        thread.setAttribute('aria-busy', state.messagesLoading ? 'true' : 'false');
        if (!items.length) {
            thread.appendChild(el('div',
                'empty' + (state.messagesLoading ? ' loading-row' : ''),
                state.messagesLoading ? 'Loading messages\u2026' : 'No messages yet \u2014 say hello'));
            return;
        }
        items.forEach((msg) => {
            const mine = state.user && msg.senderId === state.user.id;
            const row = el('div', 'msg-row ' + (mine ? 'msg-row--out' : 'msg-row--in'));
            row.dataset.msgId = msg.id;
            const bubble = el('div', 'bubble ' + (mine ? 'bubble--out' : 'bubble--in'));
            if (!mine && isGroup(conv)) {
                bubble.appendChild(el('small', 'bubble__author', msg.senderUsername || 'User'));
            }
            if (msg.forwarded && !msg.deleted) {
                bubble.appendChild(el('div', 'bubble__forwarded', 'Forwarded'));
            }
            if (msg.replyToContent) {
                bubble.appendChild(el('div', 'bubble__reply', '↳ ' + msg.replyToContent));
            }
            bubble.appendChild(el('div', 'bubble__text', msg.deleted ? 'This message was deleted.' : msg.content));
            const meta = el('div', 'bubble__meta');
            meta.appendChild(el('span', '', formatTime(msg.createdAt) + (msg.edited ? ' · edited' : '')));
            if (mine) meta.appendChild(el('span', 'ticks', msg.readByCount > 0 ? '\u2713\u2713' : '\u2713'));
            bubble.appendChild(meta);
            const reactions = el('div', 'reactions');
            (msg.reactions || []).forEach((reaction) => {
                const chip = el('button', 'reaction', reaction.emoji + ' ' + reaction.count);
                chip.type = 'button';
                chip.addEventListener('click', () => sendReaction(msg, reaction.emoji));
                reactions.appendChild(chip);
            });
            if (reactions.firstChild) bubble.appendChild(reactions);
            row.appendChild(bubble);
            if (!msg.deleted) {
                const actions = el('div', 'msg-actions');
                const reply = el('button', 'msg-action', 'Reply');
                reply.type = 'button';
                reply.addEventListener('click', () => beginReply(msg));
                actions.appendChild(reply);
                const react = el('button', 'msg-action', '😊 React');
                react.type = 'button';
                react.addEventListener('click', () => openReactionPicker(row, msg));
                actions.appendChild(react);
                const pin = el('button', 'msg-action', msg.pinned ? 'Unpin' : 'Pin');
                pin.type = 'button';
                pin.addEventListener('click', () => togglePin(msg));
                actions.appendChild(pin);
                const forward = el('button', 'msg-action', 'Forward');
                forward.type = 'button';
                forward.addEventListener('click', () => openForwardModal(msg));
                actions.appendChild(forward);
                if (mine) {
                    const edit = el('button', 'msg-action', 'Edit');
                    edit.type = 'button';
                    edit.addEventListener('click', () => openEditMessageModal(msg));
                    actions.appendChild(edit);
                }
                if (mine || isAdminConversation(conv)) {
                    const remove = el('button', 'msg-action msg-action--danger', 'Delete');
                    remove.type = 'button';
                    remove.addEventListener('click', () => removeOwnMessage(msg));
                    actions.appendChild(remove);
                }
                row.appendChild(actions);
            }
            thread.appendChild(row);
        });
        const convPage = state.pages[conv.id];
        if (convPage && !convPage.exhausted && !state.messagesLoading) {
            const more = el('button', 'load-older', convPage.loading ? 'Loading older messages...' : 'Load older messages');
            more.type = 'button';
            more.addEventListener('click', loadOlderMessages);
            thread.insertBefore(more, thread.firstChild);
        }
        thread.scrollTop = thread.scrollHeight;
    }

    // ---------- in-conversation search ----------

    function toggleChatSearch() {
        state.search.open = !state.search.open;
        $('chat-search').hidden = !state.search.open;
        if (state.search.open) $('chat-search-input').focus();
        else renderSearchResults();
    }

    function closeChatSearch() {
        state.search.open = false;
        state.search.query = '';
        state.search.results = null;
        state.search.conversationId = null;
        $('chat-search').hidden = true;
        $('chat-search-input').value = '';
        renderSearchResults();
    }

    function onSearchInput() {
        if (state.search.timer) clearTimeout(state.search.timer);
        state.search.timer = setTimeout(runConversationSearch, 300);
    }

    async function runConversationSearch() {
        const conv = state.currentConversation;
        const q = $('chat-search-input').value.trim();
        state.search.query = q;
        if (!conv || q.length < 2) {
            state.search.results = null;
            renderSearchResults();
            return;
        }
        try {
            const results = await request('/conversations/' + conv.id + '/messages/search?q=' + encodeURIComponent(q) + '&limit=25');
            if (state.search.query !== q) return;
            state.search.results = results;
            state.search.conversationId = conv.id;
            renderSearchResults();
        } catch (err) {
            state.search.results = null;
            renderSearchResults();
            toast(err.message);
        }
    }

    function renderSearchResults() {
        const box = $('chat-search-results');
        while (box.firstChild) box.removeChild(box.firstChild);
        if (!state.search.open) return;
        const conv = state.currentConversation;
        const stale = !conv || String(state.search.conversationId) !== String(conv.id);
        const results = stale ? null : state.search.results;
        if (results == null) {
            box.appendChild(el('div', 'chat-search__hint', 'Type at least two characters to search this conversation.'));
            return;
        }
        if (!results.length) {
            box.appendChild(el('div', 'chat-search__hint', 'No matching messages.'));
            return;
        }
        results.forEach((msg) => {
            const item = el('button', 'chat-search__item');
            item.type = 'button';
            item.appendChild(el('strong', 'chat-search__who', (msg.senderUsername || 'User') + ' · ' + formatTime(msg.createdAt)));
            item.appendChild(el('span', 'chat-search__text', msg.deleted ? 'This message was deleted.' : msg.content));
            item.addEventListener('click', () => jumpToMessage(msg.id));
            box.appendChild(item);
        });
    }

    function jumpToMessage(messageId) {
        const target = document.querySelector('#chat-messages [data-msg-id="' + messageId + '"]');
        if (!target) {
            toast('Open an older page of messages to find this one.');
            return;
        }
        target.scrollIntoView({ block: 'center' });
        target.classList.add('msg-row--flash');
        setTimeout(() => target.classList.remove('msg-row--flash'), 1600);
    }

    async function loadOlderMessages() {
        const conv = state.currentConversation;
        if (!conv) return;
        const page = state.pages[conv.id];
        if (!page || page.loading || page.exhausted) return;
        page.loading = true;
        renderMessages();
        try {
            const older = await request('/conversations/' + conv.id + '/messages?page=' + page.next + '&size=50');
            if (older.length === 0) page.exhausted = true;
            else state.messages.set(conv.id, older.concat(state.messages.get(conv.id) || []));
            page.next += 1;
            page.loading = false;
            renderMessages();
            const thread = $('chat-messages');
            thread.scrollTop = 60;
        } catch (err) {
            page.loading = false;
            renderMessages();
            toast(err.message);
        }
    }

    function isAdminConversation(conv) {
        return !!conv && (conv.myRole === 'OWNER' || conv.myRole === 'MODERATOR');
    }

    function renderPinnedBar() {
        const bar = $('chat-pinned');
        while (bar.firstChild) bar.removeChild(bar.firstChild);
        if (!state.pinned.length) {
            bar.hidden = true;
            return;
        }
        bar.hidden = false;
        state.pinned.slice(0, 3).forEach((msg) => {
            const line = el('div', 'pinned-bar__row');
            const label = el('button', 'pinned-bar__label', 'Pinned: ' + (msg.deleted ? 'deleted message' : msg.content));
            label.type = 'button';
            label.addEventListener('click', () => jumpToMessage(msg.id));
            line.appendChild(label);
            const unpin = el('button', 'pill pill-mute', 'Unpin');
            unpin.type = 'button';
            unpin.addEventListener('click', () => togglePin(Object.assign({}, msg, { pinned: false })));
            line.appendChild(unpin);
            bar.appendChild(line);
        });
    }

    async function loadPinned(conversationId) {
        try {
            state.pinned = await request('/conversations/' + conversationId + '/pinned');
        } catch (err) {
            state.pinned = [];
        }
        if (state.currentConversation && String(state.currentConversation.id) === String(conversationId)) {
            renderPinnedBar();
        }
    }

    async function togglePin(msg) {
        try {
            const updated = await request('/messages/' + msg.id + '/pin', {
                method: 'PUT',
                body: JSON.stringify({ pinned: !msg.pinned })
            });
            upsertMessage(updated);
            await loadPinned(updated.conversationId);
        } catch (err) {
            toast(err.message);
        }
    }

    function openForwardModal(msg) {
        const { body } = openModal('Forward message to');
        const list = el('div', 'card-list');
        body.appendChild(list);
        const conversations = state.conversations || [];
        const sameConversation = (conv) => state.currentConversation
            && String(conv.id) === String(state.currentConversation.id);
        if (!conversations.length) {
            list.appendChild(el('div', 'empty', 'No conversations yet'));
        }
        conversations.forEach((conv) => {
            const item = el('button', 'card-row');
            item.type = 'button';
            item.appendChild(el('strong', '', convTitle(conv) + (sameConversation(conv) ? ' (this chat)' : '')));
            item.addEventListener('click', () => {
                closeModal();
                forwardMessageTo(msg, conv);
            });
            list.appendChild(item);
        });
    }

    async function forwardMessageTo(msg, conv) {
        try {
            const created = await request('/conversations/' + conv.id + '/messages/forward', {
                method: 'POST',
                body: JSON.stringify({ messageId: msg.id, clientMessageId: generateClientMessageId() })
            });
            state.messages.set(conv.id, (state.messages.get(conv.id) || []).concat([created]));
            toast('Forwarded to ' + convTitle(conv));
            if (state.currentConversation && String(state.currentConversation.id) === String(conv.id)) {
                renderMessages();
            }
            renderConversations();
        } catch (err) {
            toast(err.message);
        }
    }

    let lastTypingSentAt = 0;
    let typingStopTimer = null;

    function signalTyping() {
        const conv = state.currentConversation;
        if (!conv || !state.stomp) return;
        const now = Date.now();
        if (now - lastTypingSentAt > 1500) {
            lastTypingSentAt = now;
            state.stomp.send('/app/conversations/' + conv.id + '/typing', { typing: true });
        }
        if (typingStopTimer) clearTimeout(typingStopTimer);
        typingStopTimer = setTimeout(() => {
            lastTypingSentAt = 0;
            if (state.stomp) state.stomp.send('/app/conversations/' + conv.id + '/typing', { typing: false });
        }, 3000);
    }

    function recordTyping(conversationId, username, typing) {
        if (username && state.user && username === state.user.username) return;
        let peers = state.typingPeers.get(conversationId);
        if (!peers) {
            peers = new Map();
            state.typingPeers.set(conversationId, peers);
        }
        if (typing) peers.set(username, Date.now());
        else peers.delete(username);
        renderTypingBar();
    }

    function renderTypingBar() {
        const bar = $('chat-typing');
        const conv = state.currentConversation;
        if (!bar || !conv) return;
        const peers = state.typingPeers.get(conv.id) || new Map();
        const now = Date.now();
        peers.forEach((seen, name) => { if (now - seen > 6000) peers.delete(name); });
        const names = [...peers.keys()];
        if (!names.length) {
            bar.hidden = true;
            bar.textContent = '';
            return;
        }
        bar.hidden = false;
        bar.textContent = (names.length === 1
            ? names[0] + ' is typing'
            : names.slice(0, 3).join(', ') + ' are typing') + '\u2026';
    }

    function beginReply(msg) {
        state.replyTo = msg;
        $('reply-bar-author').textContent = 'Replying to ' + (msg.senderUsername || 'message');
        $('reply-bar-preview').textContent = msg.deleted ? 'Deleted message' : msg.content;
        $('reply-bar').hidden = false;
        $('message-content-input').focus();
    }

    function clearReply() {
        state.replyTo = null;
        $('reply-bar').hidden = true;
        $('reply-bar-author').textContent = '';
        $('reply-bar-preview').textContent = '';
    }

    function openReactionPicker(parent, msg) {
        const existing = parent.querySelector('.emoji-picker');
        if (existing) {
            parent.removeChild(existing);
            return;
        }
        const picker = el('div', 'emoji-picker');
        REACTION_EMOJIS.forEach((emoji) => {
            const btn = el('button', 'emoji-picker__item', emoji);
            btn.type = 'button';
            btn.addEventListener('click', () => {
                if (picker.parentNode === parent) parent.removeChild(picker);
                sendReaction(msg, emoji);
            });
            picker.appendChild(btn);
        });
        parent.appendChild(picker);
    }

    async function sendReaction(msg, emoji) {
        try {
            const updated = await request('/messages/' + msg.id + '/reactions', {
                method: 'POST',
                body: JSON.stringify({ emoji: emoji })
            });
            upsertMessage(updated);
        } catch (err) {
            toast(err.message);
        }
    }

    function openEditMessageModal(msg) {
        const { body, actions } = openModal('Edit message');
        const area = el('textarea');
        area.rows = 3;
        area.maxLength = 2000;
        area.value = msg.content || '';
        body.appendChild(area);
        const save = el('button', 'btn btn-primary', 'Save');
        save.type = 'button';
        save.addEventListener('click', async () => {
            const content = area.value.trim();
            if (!content) {
                toast('Message cannot be empty');
                return;
            }
            try {
                const updated = await request('/messages/' + msg.id, {
                    method: 'PUT',
                    body: JSON.stringify({ content: content })
                });
                closeModal();
                upsertMessage(updated);
            } catch (err) {
                toast(err.message);
            }
        });
        actions.appendChild(save);
        const cancel = el('button', 'btn btn-ghost', 'Cancel');
        cancel.type = 'button';
        cancel.addEventListener('click', closeModal);
        actions.appendChild(cancel);
    }

    async function removeOwnMessage(msg) {
        try {
            await request('/messages/' + msg.id, { method: 'DELETE' });
            const conversationId = msg.conversationId || (state.currentConversation && state.currentConversation.id);
            removeMessage(conversationId, msg.id);
        } catch (err) {
            toast(err.message);
        }
    }

    function removeMessage(conversationId, messageId) {
        if (!conversationId || !messageId) return;
        const list = state.messages.get(conversationId) || [];
        state.messages.set(conversationId, list.filter((item) => item.id !== messageId));
        if (state.currentConversation && state.currentConversation.id === conversationId) {
            renderMessages();
        }
    }

    function upsertMessage(msg) {
        if (!msg || !msg.conversationId) return;
        const list = state.messages.get(msg.conversationId) || [];
        const idx = list.findIndex((item) => item.id === msg.id);
        if (idx >= 0) list[idx] = msg;
        else list.push(msg);
        state.messages.set(msg.conversationId, list);
        const conv = (state.conversations || []).find((item) => item.id === msg.conversationId);
        if (conv) {
            conv.lastMessageText = msg.deleted ? 'This message was deleted.' : msg.content;
            conv.lastMessageAt = msg.createdAt;
        }
        if (state.currentConversation && state.currentConversation.id === msg.conversationId) {
            renderMessages();
            if (state.user && String(msg.senderId) !== String(state.user.id)) markRead(msg.conversationId);
        }
        renderConversations();
    }

    async function openConversation(id) {
        const conv = (state.conversations || []).find((item) => item.id === id);
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
        $('chat-subtitle').textContent = isGroup(conv) ? (conv.memberCount ? conv.memberCount + ' members' : 'Group chat') : 'Private chat';
        $('chat-online-dot').hidden = isGroup(conv);
        renderConversations();
        subscribeConversation(id);
        clearReply();
        state.pinned = [];
        renderPinnedBar();
        loadPinned(id);
        closeChatSearch();
        if (isGroup(conv) && !conv.myRole) {
            try {
                const members = await request('/conversations/' + id + '/members');
                const me = members.find((member) => state.user && member.userId === state.user.id);
                conv.myRole = me ? me.role : null;
            } catch (err) {
                conv.myRole = null;
            }
        }
        if (!state.messages.has(id)) {
            state.messagesLoading = true;
            renderMessages();
        }
        try {
            const messages = await request('/conversations/' + id + '/messages?size=50');
            state.messages.set(id, messages);
            state.pages[id] = { next: 1, loading: false, exhausted: messages.length < 50 };
        } catch (err) {
            toast(err.message);
        } finally {
            state.messagesLoading = false;
            renderMessages();
        }
        markRead(id);
    }

    let lastMarkReadAt = 0;

    function markRead(conversationId) {
        const conv = (state.conversations || []).find((item) => String(item.id) === String(conversationId));
        if (!conv || conv.unreadCount > 0 || Date.now() - lastMarkReadAt > 15000) {
            lastMarkReadAt = Date.now();
            request('/conversations/' + conversationId + '/read', { method: 'PUT', body: '{}' })
                .then(() => { if (conv) conv.unreadCount = 0; renderConversations(); })
                .catch(() => {});
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
        const [friends, incoming, outgoing, invitations] = await Promise.all([
            request('/friends'),
            request('/friends/requests/incoming'),
            request('/friends/requests/outgoing'),
            request('/conversations/invitations')
        ]);
        state.friends = friends;
        state.incoming = incoming;
        state.outgoing = outgoing;
        state.groupInvitations = invitations;
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

    async function loadDiscoverGroups() {
        const query = $('group-discover-search').value.trim();
        try {
            state.discoverGroups = await request('/conversations/groups/discover?q=' + encodeURIComponent(query));
            renderDiscoverGroups();
        } catch (err) {
            toast(err.message);
        }
    }

    function renderDiscoverGroups() {
        const list = $('discover-groups-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!state.discoverGroups.length) {
            list.appendChild(el('div', 'empty', 'No groups found'));
            return;
        }
        state.discoverGroups.forEach((group) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, group.title, group.avatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', group.title));
            mid.appendChild(el('span', 'muted', group.memberCount + ' members · ' + (group.isPrivate ? 'Private' : 'Public')));
            row.appendChild(avatar);
            row.appendChild(mid);
            const action = el('div', 'row-gap');
            if (group.membershipStatus === 'MEMBER') {
                const open = el('button', 'pill pill-ok', 'Open');
                open.type = 'button';
                open.addEventListener('click', () => {
                    closeModal();
                    openView('chats', { conversationId: group.id });
                });
                action.appendChild(open);
            } else if (group.membershipStatus === 'PENDING') {
                const pending = el('span', 'pill pill-mute', 'Pending');
                action.appendChild(pending);
            } else if (group.membershipStatus === 'BLOCKED') {
                action.appendChild(el('span', 'pill pill-no', 'Blocked'));
            } else if (group.isPrivate) {
                const requestJoin = el('button', 'pill pill-ok', 'Request to Join');
                requestJoin.type = 'button';
                requestJoin.addEventListener('click', async () => {
                    try {
                        await request('/conversations/groups/' + group.id + '/join-requests', { method: 'POST' });
                        toast('Join request sent');
                        group.membershipStatus = 'PENDING';
                        renderDiscoverGroups();
                    } catch (err) {
                        toast(err.message);
                    }
                });
                action.appendChild(requestJoin);
            } else {
                const join = el('button', 'pill pill-ok', 'Join');
                join.type = 'button';
                join.addEventListener('click', async () => {
                    try {
                        await request('/conversations/groups/' + group.id + '/join', { method: 'POST' });
                        toast('Joined group');
                        await loadConversations();
                        group.membershipStatus = 'MEMBER';
                        renderDiscoverGroups();
                    } catch (err) {
                        toast(err.message);
                    }
                });
                action.appendChild(join);
            }
            row.appendChild(action);
            list.appendChild(row);
        });
    }

    async function loadGroupJoinRequests() {
        try {
            state.groupJoinRequests = await request('/conversations/join-requests');
        } catch (err) {
            toast(err.message);
        }
    }

    function renderGroupInvitationsList() {
        const list = $('group-invitations-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!state.groupInvitations.length) {
            list.appendChild(el('div', 'empty', 'No group invitations'));
            return;
        }
        state.groupInvitations.forEach((inv) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, inv.groupName, inv.groupAvatar);
            const mid = el('div');
            mid.appendChild(el('strong', '', inv.groupName));
            mid.appendChild(el('span', 'muted', 'Invited by ' + inv.inviterDisplayName));
            row.appendChild(avatar);
            row.appendChild(mid);
            const action = el('div', 'row-gap');
            const accept = el('button', 'pill pill-ok', 'Accept');
            const reject = el('button', 'pill pill-no', 'Reject');
            accept.type = 'button';
            reject.type = 'button';
            accept.addEventListener('click', async () => {
                try {
                    await request('/conversations/invitations/' + inv.id + '/accept', { method: 'POST' });
                    toast('Invitation accepted');
                    await loadFriends();
                    await loadConversations();
                    renderGroupInvitationsList();
                } catch (err) {
                    toast(err.message);
                }
            });
            reject.addEventListener('click', async () => {
                try {
                    await request('/conversations/invitations/' + inv.id + '/reject', { method: 'POST' });
                    toast('Invitation rejected');
                    await loadFriends();
                    renderGroupInvitationsList();
                } catch (err) {
                    toast(err.message);
                }
            });
            action.appendChild(accept);
            action.appendChild(reject);
            row.appendChild(action);
            list.appendChild(row);
        });
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

    async function fetchDirectory(query) {
        const page = await request('/users/search?query=' + encodeURIComponent(query || '') + '&size=50');
        const content = page.content || page || [];
        state.directory = content.filter((person) => !state.user || person.id !== state.user.id);
        return state.directory;
    }

    function openNewPrivateModal() {
        const { body, actions } = openModal('Start a private chat');
        const input = el('input');
        input.placeholder = 'Search people from ChatSphere';
        const results = el('div', 'card-list');
        body.appendChild(input);
        body.appendChild(results);
        async function draw(query) {
            while (results.firstChild) results.removeChild(results.firstChild);
            try {
                const people = await fetchDirectory(query);
                if (!people.length) {
                    results.appendChild(el('div', 'empty', 'No users found'));
                    return;
                }
                people.forEach((person) => {
                    const row = el('button', 'card-row');
                    row.type = 'button';
                    const avatar = el('div', 'avatar');
                    setAvatar(avatar, person.displayName, person.avatarUrl);
                    const mid = el('div');
                    mid.appendChild(el('strong', '', person.displayName));
                    mid.appendChild(el('span', 'muted', '@' + person.username + (person.online ? ' · Online' : '')));
                    row.appendChild(avatar);
                    row.appendChild(mid);
                    row.addEventListener('click', () => {
                        closeModal();
                        startDirect(person.id);
                    });
                    results.appendChild(row);
                });
            } catch (err) {
                toast(err.message);
            }
        }
        draw('');
        input.addEventListener('input', () => draw(input.value));
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
        const privacy = el('label', 'pref');
        privacy.appendChild(el('span', '', 'Private group (join by request only)'));
        const privacySwitch = el('span', 'switch');
        const privateBox = document.createElement('input');
        privateBox.type = 'checkbox';
        const privacySlider = el('span', 'slider');
        privacySwitch.appendChild(privateBox);
        privacySwitch.appendChild(privacySlider);
        privacy.appendChild(privacySwitch);
        const search = el('input');
        search.placeholder = 'Search users to add as members';
        const picks = el('div', 'card-list');
        const selected = new Set();
        body.appendChild(title);
        body.appendChild(desc);
        body.appendChild(privacy);
        body.appendChild(el('p', 'muted', 'Add members from ChatSphere'));
        body.appendChild(search);
        body.appendChild(picks);

        async function draw(query) {
            while (picks.firstChild) picks.removeChild(picks.firstChild);
            try {
                const people = await fetchDirectory(query);
                if (!people.length) {
                    picks.appendChild(el('div', 'empty', 'No users found'));
                    return;
                }
                people.forEach((person) => {
                    const row = el('label', 'card-row');
                    const box = document.createElement('input');
                    box.type = 'checkbox';
                    box.checked = selected.has(person.id);
                    box.addEventListener('change', () => {
                        if (box.checked) selected.add(person.id);
                        else selected.delete(person.id);
                    });
                    const avatar = el('div', 'avatar');
                    setAvatar(avatar, person.displayName, person.avatarUrl);
                    const mid = el('div');
                    mid.appendChild(el('strong', '', person.displayName));
                    mid.appendChild(el('span', 'muted', '@' + person.username));
                    row.appendChild(box);
                    row.appendChild(mid);
                    row.insertBefore(avatar, mid);
                    picks.appendChild(row);
                });
            } catch (err) {
                toast(err.message);
            }
        }
        draw('');
        search.addEventListener('input', () => draw(search.value));

        const create = el('button', 'btn btn-primary', 'Create group');
        create.type = 'button';
        create.addEventListener('click', async () => {
            try {
                const conv = await request('/conversations/group', {
                    method: 'POST',
                    body: JSON.stringify({
                        title: title.value,
                        description: desc.value,
                        memberIds: Array.from(selected),
                        isPrivate: privateBox.checked
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

    function switchGroupTab(tab) {
        document.querySelectorAll('.group-menu__item').forEach((item) => {
            item.classList.toggle('is-active', item.dataset.gtab === tab);
        });
        document.querySelectorAll('.group-pane').forEach((pane) => {
            pane.hidden = pane.dataset.gpane !== tab;
        });
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
            state.groupMembers = members;
            const me = members.find((member) => state.user && member.userId === state.user.id);
            conv.myRole = me ? me.role : null;
            const isAdmin = conv.myRole === 'OWNER' || conv.myRole === 'MODERATOR';
            openView('group');
            $('group-manage-title').textContent = details.title || convTitle(conv);
            $('group-manage-meta').textContent = members.length + ' members · Group' + (conv.myRole ? ' · You are ' + conv.myRole.toLowerCase() : '');
            $('group-members-heading').textContent = 'Members (' + members.length + ')';
            $('group-title-input').value = details.title || '';
            $('group-desc-input').value = details.description || '';
            $('group-title-input').disabled = !isAdmin;
            $('group-desc-input').disabled = !isAdmin;
            $('group-private-input').checked = !!details.isPrivate;
            $('group-private-input').disabled = !isAdmin;
            $('group-save-btn').disabled = !isAdmin;
            $('group-created').textContent = details.createdAt
                ? 'Created ' + new Date(details.createdAt).toLocaleDateString()
                : 'Created recently';
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
                const isSelf = member.userId === state.user.id;
                if (!isSelf && isAdmin) {
                    const actions = el('div', 'row-gap');
                    if (conv.myRole === 'OWNER' && member.role === 'MEMBER') {
                        const promote = el('button', 'pill pill-ok', 'Promote');
                        promote.type = 'button';
                        promote.addEventListener('click', () => changeMemberRole(conv.id, member.userId, 'MODERATOR'));
                        actions.appendChild(promote);
                    }
                    if (conv.myRole === 'OWNER' && member.role === 'MODERATOR') {
                        const demote = el('button', 'pill pill-mute', 'Demote');
                        demote.type = 'button';
                        demote.addEventListener('click', () => changeMemberRole(conv.id, member.userId, 'MEMBER'));
                        actions.appendChild(demote);
                    }
                    const canRemove = conv.myRole === 'OWNER' || member.role === 'MEMBER';
                    if (canRemove) {
                        const remove = el('button', 'pill pill-no', 'Remove');
                        remove.type = 'button';
                        remove.addEventListener('click', async () => {
                            try {
                                await request('/conversations/' + conv.id + '/members/' + member.userId, { method: 'DELETE' });
                                toast('Member removed');
                                openGroupManage();
                            } catch (err) {
                                toast(err.message);
                            }
                        });
                        actions.appendChild(remove);
                    }
                    if (member.role === 'MEMBER') {
                        const block = el('button', 'pill pill-no', 'Block');
                        block.type = 'button';
                        block.addEventListener('click', async () => {
                            try {
                                await request('/conversations/groups/' + conv.id + '/blocks', {
                                    method: 'POST',
                                    body: JSON.stringify({ userId: member.userId })
                                });
                                toast('User blocked from group');
                                openGroupManage();
                            } catch (err) {
                                toast(err.message);
                            }
                        });
                        actions.appendChild(block);
                    }
                    row.appendChild(actions);
                }
                list.appendChild(row);
            });

            $('add-member-btn').hidden = !isAdmin;
            document.querySelectorAll('.group-menu__item[data-gtab="requests"], .group-menu__item[data-gtab="invitations"], .group-menu__item[data-gtab="blocked"]')
                .forEach((item) => { item.hidden = !isAdmin; });
            if (!isAdmin) switchGroupTab('info');

            if (isAdmin) {
                try {
                    const joinRequests = await request('/conversations/groups/' + conv.id + '/join-requests');
                    const invitations = await request('/conversations/groups/' + conv.id + '/invitations');
                    renderGroupJoinRequests(joinRequests);
                    renderGroupInvitations(invitations);
                } catch (err) {
                    console.error('Failed to load join requests/invitations:', err);
                }
                try {
                    state.groupBlocked = await request('/conversations/groups/' + conv.id + '/blocks');
                    renderGroupBlocked();
                } catch (err) {
                    console.error('Failed to load blocked members:', err);
                }
            }
        } catch (err) {
            toast(err.message);
        }
    }

    async function changeMemberRole(groupId, memberId, role) {
        try {
            await request('/conversations/groups/' + groupId + '/members/' + memberId + '/role?role=' + role, {
                method: 'PUT'
            });
            toast(role === 'MODERATOR' ? 'Member promoted to moderator' : 'Moderator demoted');
            openGroupManage();
        } catch (err) {
            toast(err.message);
        }
    }

    function renderGroupBlocked() {
        const list = $('group-blocked-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        const blocks = state.groupBlocked || [];
        if (!blocks.length) {
            list.appendChild(el('div', 'empty', 'No blocked users'));
            return;
        }
        const conv = state.currentConversation;
        blocks.forEach((blocked) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, blocked.displayName, blocked.avatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', blocked.displayName));
            mid.appendChild(el('span', 'muted', '@' + blocked.username));
            row.appendChild(avatar);
            row.appendChild(mid);
            const unblock = el('button', 'pill pill-ok', 'Unblock');
            unblock.type = 'button';
            unblock.addEventListener('click', async () => {
                try {
                    await request('/conversations/groups/' + conv.id + '/blocks/' + blocked.userId, { method: 'DELETE' });
                    toast('User unblocked');
                    openGroupManage();
                } catch (err) {
                    toast(err.message);
                }
            });
            row.appendChild(unblock);
            list.appendChild(row);
        });
    }

    function renderGroupJoinRequests(requests) {
        const list = $('group-join-requests-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!requests.length) {
            list.appendChild(el('div', 'empty', 'No pending join requests'));
            return;
        }
        requests.forEach((req) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, req.displayName, req.userAvatar);
            const mid = el('div');
            mid.appendChild(el('strong', '', req.displayName));
            mid.appendChild(el('span', 'muted', '@' + req.username));
            row.appendChild(avatar);
            row.appendChild(mid);
            const action = el('div', 'row-gap');
            const accept = el('button', 'pill pill-ok', 'Accept');
            const reject = el('button', 'pill pill-no', 'Reject');
            accept.type = 'button';
            reject.type = 'button';
            accept.addEventListener('click', async () => {
                try {
                    await request('/conversations/groups/' + req.groupId + '/join-requests/' + req.id + '/accept', { method: 'POST' });
                    toast('Join request accepted');
                    openGroupManage();
                } catch (err) {
                    toast(err.message);
                }
            });
            reject.addEventListener('click', async () => {
                try {
                    await request('/conversations/groups/' + req.groupId + '/join-requests/' + req.id + '/reject', { method: 'POST' });
                    toast('Join request rejected');
                    openGroupManage();
                } catch (err) {
                    toast(err.message);
                }
            });
            action.appendChild(accept);
            action.appendChild(reject);
            row.appendChild(action);
            list.appendChild(row);
        });
    }

    function renderGroupInvitations(invitations) {
        const list = $('pending-group-invitations-list');
        while (list.firstChild) list.removeChild(list.firstChild);
        if (!invitations.length) {
            list.appendChild(el('div', 'empty', 'No pending invitations'));
            return;
        }
        invitations.forEach((inv) => {
            const row = el('div', 'card-row');
            const avatar = el('div', 'avatar');
            setAvatar(avatar, inv.inviterDisplayName, inv.inviterAvatar);
            const mid = el('div');
            mid.appendChild(el('strong', '', inv.inviterDisplayName));
            mid.appendChild(el('span', 'muted', 'Invited: ' + (inv.invitedUserId ? 'User' : 'Unknown')));
            row.appendChild(avatar);
            row.appendChild(mid);
            const cancel = el('button', 'pill pill-mute', 'Cancel');
            cancel.type = 'button';
            cancel.addEventListener('click', async () => {
                try {
                    await request('/conversations/invitations/' + inv.id, { method: 'DELETE' });
                    toast('Invitation cancelled');
                    openGroupManage();
                } catch (err) {
                    toast(err.message);
                }
            });
            row.appendChild(cancel);
            list.appendChild(row);
        });
    }

    function openAddMemberModal() {
        const conv = state.currentConversation;
        const { body, actions } = openModal('Add members');
        const input = el('input');
        input.placeholder = 'Search users from the database...';
        const results = el('div', 'card-list');
        const selected = new Set();
        const memberIds = new Set((state.groupMembers || []).map((member) => member.userId));
        body.appendChild(el('p', 'muted', 'Choose registered users to add to this group'));
        body.appendChild(input);
        body.appendChild(results);

        async function searchUsersForInvite(query) {
            while (results.firstChild) results.removeChild(results.firstChild);
            try {
                const people = await fetchDirectory(query);
                const available = people.filter((person) => !memberIds.has(person.id));
                if (!available.length) {
                    results.appendChild(el('div', 'empty', 'No other users available'));
                    return;
                }
                available.forEach(renderUserOption);
            } catch (err) {
                toast(err.message);
            }
        }

        function renderUserOption(person) {
            const row = el('label', 'card-row');
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = selected.has(person.id);
            box.addEventListener('change', () => {
                if (box.checked) selected.add(person.id);
                else selected.delete(person.id);
            });
            const avatar = el('div', 'avatar');
            setAvatar(avatar, person.displayName, person.avatarUrl);
            const mid = el('div');
            mid.appendChild(el('strong', '', person.displayName));
            mid.appendChild(el('span', 'muted', '@' + person.username + (person.online ? ' · Online' : '')));
            row.appendChild(box);
            row.appendChild(avatar);
            row.appendChild(mid);
            results.appendChild(row);
        }

        input.addEventListener('input', () => searchUsersForInvite(input.value));
        searchUsersForInvite('');

        const add = el('button', 'btn btn-primary', 'Add members');
        add.type = 'button';
        add.addEventListener('click', async () => {
            if (selected.size === 0) {
                toast('Select at least one user');
                return;
            }
            try {
                const ids = Array.from(selected);
                for (const userId of ids) {
                    await request('/conversations/' + conv.id + '/members', {
                        method: 'POST',
                        body: JSON.stringify({ userId: userId })
                    });
                }
                closeModal();
                toast('Members added');
                openGroupManage();
            } catch (err) {
                toast(err.message);
            }
        });
        actions.appendChild(add);
        const invite = el('button', 'btn btn-outline', 'Send invitations');
        invite.type = 'button';
        invite.addEventListener('click', async () => {
            if (selected.size === 0) {
                toast('Select at least one user');
                return;
            }
            try {
                await request('/conversations/groups/' + conv.id + '/invitations', {
                    method: 'POST',
                    body: JSON.stringify({ userIds: Array.from(selected) })
                });
                closeModal();
                toast('Invitations sent');
            } catch (err) {
                toast(err.message);
            }
        });
        actions.appendChild(invite);
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
            if (frame.event === 'NEW_MESSAGE' || frame.event === 'MESSAGE_EDITED' || frame.event === 'MESSAGE_REACTED') {
                upsertMessage(frame.data);
            } else if (frame.event === 'MESSAGE_DELETED') {
                removeMessage(frame.data && frame.data.conversationId, frame.data && frame.data.messageId);
            } else if (frame.event === 'TYPING') {
                recordTyping(frame.conversationId, frame.data && frame.data.username, frame.data && frame.data.typing);
            } else if (frame.event === 'MESSAGE_PINNED') {
                upsertMessage(frame.data);
                if (state.currentConversation && String(frame.conversationId) === String(state.currentConversation.id)) {
                    loadPinned(frame.conversationId);
                }
            } else if (frame.event === 'READ_UPDATE') {
                if (state.user && String(frame.data && frame.data.userId) === String(state.user.id)) return;
                if (state.currentConversation && String(frame.conversationId) === String(state.currentConversation.id)) {
                    request('/conversations/' + frame.conversationId + '/messages?size=50')
                        .then((messages) => {
                            state.messages.set(state.currentConversation.id, messages);
                            renderMessages();
                        })
                        .catch(() => {});
                }
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
            client.subscribe(DEST.USERS, () => {});
            client.subscribe(DEST.ERROR_BOX, (err) => {
                if (err && err.message) toast(err.message);
            });
            if (state.user) {
                client.send(DEST.JOIN, { username: state.user.username });
            }
            (state.conversations || []).forEach((conv) => subscribeConversation(conv.id));
        }, () => {
            // Stop reconnecting once the user has logged out; the server now
            // refuses subscriptions from anonymous connections anyway.
            if (!state.user) return;
            setTimeout(connectWebSocket, 4000);
        });
    }

    function setupAuth() {
        $('show-register-btn').addEventListener('click', () => showAuth('register'));
        $('show-login-btn').addEventListener('click', () => showAuth('login'));
        $('forgot-password-btn').addEventListener('click', () => toast('Ask an admin to reset the account password'));
        document.querySelectorAll('.social-btn').forEach((btn) => {
            btn.addEventListener('click', () => toast(btn.dataset.social + ' sign-in is coming soon'));
        });
        $('login-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await withBusy($('login-submit'), async () => {
                    await request('/auth/login', {
                        method: 'POST',
                        body: JSON.stringify({
                            email: $('login-email').value,
                            password: $('login-password').value
                        })
                    });
                    await bootAuthenticated();
                });
            } catch (err) {
                showError($('login-error'), err.message);
            }
        });
        $('register-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await withBusy($('register-submit'), async () => {
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
                });
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
        $('new-chat-btn').addEventListener('click', openNewPrivateModal);
        $('chat-call-btn').addEventListener('click', () => toast('Voice calls are coming soon'));
        $('chat-search-btn').addEventListener('click', toggleChatSearch);
        $('chat-search-close').addEventListener('click', closeChatSearch);
        $('chat-search-input').addEventListener('input', onSearchInput);
        $('chat-search-input').addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
                event.preventDefault();
                runConversationSearch();
            } else if (event.key === 'Escape') {
                closeChatSearch();
            }
        });
        $('emoji-btn').addEventListener('click', () => {
            const input = $('message-content-input');
            input.value += '😊';
            input.focus();
        });
        $('attach-btn').addEventListener('click', () => toast('File attachments are coming soon'));
        document.querySelectorAll('.group-menu__item').forEach((item) => {
            item.addEventListener('click', () => switchGroupTab(item.dataset.gtab));
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
                $('group-invites-pane').hidden = tab.dataset.reqtab !== 'group-invites';
                if (tab.dataset.reqtab === 'group-invites') {
                    renderGroupInvitationsList();
                }
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
        $('user-search-input').addEventListener('input', () => {
            clearTimeout(state.searchTimer);
            state.searchTimer = setTimeout(searchUsers, 250);
        });
        $('group-discover-search-btn').addEventListener('click', loadDiscoverGroups);
        $('group-discover-search').addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
                event.preventDefault();
                loadDiscoverGroups();
            }
        });
        $('modal-close').addEventListener('click', closeModal);
        $('modal-backdrop').addEventListener('click', closeModal);
        $('add-member-btn').addEventListener('click', openAddMemberModal);
        $('group-leave-btn').addEventListener('click', async () => {
            const conv = state.currentConversation;
            if (!conv || !isGroup(conv)) {
                toast('Open a group chat first');
                return;
            }
            try {
                await request('/conversations/' + conv.id + '/leave', { method: 'POST' });
                toast('Left the group');
                state.currentConversation = null;
                await loadConversations();
                openView('chats');
            } catch (err) {
                toast(err.message);
            }
        });
        $('group-save-btn').addEventListener('click', async () => {
            try {
                await request('/conversations/' + state.currentConversation.id, {
                    method: 'PUT',
                    body: JSON.stringify({
                        title: $('group-title-input').value,
                        description: $('group-desc-input').value,
                        isPrivate: $('group-private-input').checked
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
            const people = await fetchDirectory(query);
            if (!people.length) {
                list.appendChild(el('div', 'empty', 'No other users found'));
                return;
            }
            people.forEach((person) => {
                const row = el('div', 'card-row');
                const avatar = el('div', 'avatar');
                setAvatar(avatar, person.displayName, person.avatarUrl);
                const mid = el('div');
                mid.appendChild(el('strong', '', person.displayName));
                mid.appendChild(el('span', 'muted', '@' + person.username + (person.online ? ' · Online' : '')));
                row.appendChild(avatar);
                row.appendChild(mid);
                row.appendChild(friendActionButton(person));
                list.appendChild(row);
            });
        } catch (err) {
            toast(err.message);
        }
    }

    function friendActionButton(person) {
        const status = person.friendshipStatus || 'NONE';
        if (status === 'FRIENDS') {
            const message = el('button', 'pill pill-ok', 'Message');
            message.type = 'button';
            message.addEventListener('click', () => startDirect(person.id));
            return message;
        }
        if (status === 'PENDING_SENT') {
            return el('button', 'pill pill-mute', 'Requested');
        }
        if (status === 'PENDING_RECEIVED') {
            const accept = el('button', 'pill pill-ok', 'Accept');
            accept.type = 'button';
            accept.addEventListener('click', async () => {
                try {
                    await request('/friends/requests', {
                        method: 'POST',
                        body: JSON.stringify({ userId: person.id, username: person.username })
                    });
                    toast('Friend request accepted');
                    await loadFriends();
                    searchUsers();
                } catch (err) {
                    toast(err.message);
                }
            });
            return accept;
        }
        const add = el('button', 'pill pill-ok', 'Add friend');
        add.type = 'button';
        add.addEventListener('click', () => openFriendRequestModal(person));
        return add;
    }

    function openFriendRequestModal(person) {
        const { body, actions } = openModal('Friend request to ' + (person.displayName || person.username));
        const note = el('textarea');
        note.rows = 3;
        note.maxLength = 280;
        note.placeholder = 'Add a short message (optional)';
        body.appendChild(note);
        const send = el('button', 'btn btn-primary', 'Send request');
        send.type = 'button';
        send.addEventListener('click', async () => {
            try {
                await request('/friends/requests', {
                    method: 'POST',
                    body: JSON.stringify({
                        username: person.username,
                        userId: person.id,
                        message: note.value.trim() || null
                    })
                });
                closeModal();
                toast('Friend request sent');
                await loadFriends();
                searchUsers();
            } catch (err) {
                toast(err.message);
            }
        });
        actions.appendChild(send);
        const cancel = el('button', 'btn btn-ghost', 'Cancel');
        cancel.type = 'button';
        cancel.addEventListener('click', closeModal);
        actions.appendChild(cancel);
    }

    function setupChat() {
        $('reply-cancel-btn').addEventListener('click', clearReply);
        $('message-content-input').addEventListener('keydown', signalTyping);
        setInterval(renderTypingBar, 2000);
        $('message-send-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            const conv = state.currentConversation;
            const input = $('message-content-input');
            const content = input.value.trim();
            if (!conv || !content) return;
            const payload = {
                content: content,
                replyToId: state.replyTo ? state.replyTo.id : null,
                clientMessageId: generateClientMessageId()
            };
            input.value = '';
            clearReply();
            try {
                if (state.stomp) {
                    state.stomp.send('/app/conversations/' + conv.id + '/send', payload);
                } else {
                    await withBusy($('message-send-btn'), async () => {
                        const msg = await request('/conversations/' + conv.id + '/messages', {
                            method: 'POST',
                            body: JSON.stringify(payload)
                        });
                        upsertMessage(msg);
                    });
                }
            } catch (err) {
                toast(err.message);
            }
        });
    }

    function generateClientMessageId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        return 'cm-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    }

    function setupSettings() {
        $('theme-light').addEventListener('click', () => applyTheme('light'));
        $('theme-dark').addEventListener('click', () => applyTheme('dark'));
        $('save-prefs-btn').addEventListener('click', async () => {
            try {
                await withBusy($('save-prefs-btn'), async () => {
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
                });
            } catch (err) {
                toast(err.message);
            }
        });
        $('profile-form').addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await withBusy(event.submitter, async () => {
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
                });
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
            // The landing page's "Get Started" deep-links #signup here;
            // everyone else lands on the login screen.
            showAuth(location.hash === '#signup' ? 'register' : 'login');
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
