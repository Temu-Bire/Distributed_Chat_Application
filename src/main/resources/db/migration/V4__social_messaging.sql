-- Distributed ChatApp schema V4: reply, reactions, privacy, moderation, blocks

ALTER TABLE friend_requests ADD COLUMN message VARCHAR(280);

ALTER TABLE conversations ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE messages ADD COLUMN reply_to_id UUID;
ALTER TABLE messages ADD COLUMN client_message_id VARCHAR(64);
ALTER TABLE messages ADD CONSTRAINT fk_msg_reply FOREIGN KEY (reply_to_id) REFERENCES messages(id) ON DELETE SET NULL;
ALTER TABLE messages ADD CONSTRAINT uq_msg_client_id UNIQUE (conversation_id, client_message_id);

CREATE TABLE IF NOT EXISTS message_reactions (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    user_id UUID NOT NULL,
    emoji VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_message_reaction UNIQUE (message_id, user_id, emoji),
    CONSTRAINT fk_reaction_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reaction_message ON message_reactions (message_id);

CREATE TABLE IF NOT EXISTS group_blocks (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    user_id UUID NOT NULL,
    blocked_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_group_block UNIQUE (group_id, user_id),
    CONSTRAINT fk_block_group FOREIGN KEY (group_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_block_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_block_by FOREIGN KEY (blocked_by) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_block_group ON group_blocks (group_id, user_id);
