-- Distributed ChatApp schema V5: pins, attachments, forwarding, notification preferences, invite links

ALTER TABLE messages ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD COLUMN pinned_by UUID;
ALTER TABLE messages ADD COLUMN pinned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE messages ADD COLUMN attachment_url VARCHAR(500);
ALTER TABLE messages ADD COLUMN attachment_name VARCHAR(150);
ALTER TABLE messages ADD COLUMN attachment_type VARCHAR(60);
ALTER TABLE messages ADD COLUMN attachment_size BIGINT;
ALTER TABLE messages ADD COLUMN forwarded_from_id UUID;
ALTER TABLE messages ADD CONSTRAINT fk_msg_pinned_by FOREIGN KEY (pinned_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE messages ADD CONSTRAINT fk_msg_forwarded FOREIGN KEY (forwarded_from_id) REFERENCES messages(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_msg_pinned ON messages (conversation_id, pinned);

ALTER TABLE user_settings ADD COLUMN notify_messages BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN notify_mentions BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN notify_friend_requests BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE user_settings ADD COLUMN notify_group_activity BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS group_invite_links (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    token VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    max_uses INTEGER,
    used_count INTEGER NOT NULL DEFAULT 0,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_invite_token UNIQUE (token),
    CONSTRAINT fk_invite_group FOREIGN KEY (group_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_invite_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE
);
