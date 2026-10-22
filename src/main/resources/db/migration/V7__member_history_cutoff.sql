-- Distributed ChatApp schema V7: a per-member history cutoff, so "clear this chat for me"
-- can wipe one person's view of a conversation without touching anybody else's copy.
--
-- cleared_at is the boundary: a member sees no message created at or before their own
-- cleared_at, and the conversation stays out of their list until a message arrives after
-- it. NULL means "never cleared", which is every membership row written before this column
-- existed, so existing histories are unaffected.

ALTER TABLE conversation_members ADD COLUMN cleared_at TIMESTAMP WITH TIME ZONE;

-- A bulk forward stamps one client id per source message ("<batch-id>:<message-uuid>"),
-- so a retried batch replays instead of duplicating. That is longer than a single send's
-- id, and the column must hold it without truncating - a truncated id would collide with
-- another message's id and the unique key would reject a legitimate forward.
ALTER TABLE messages ALTER COLUMN client_message_id SET DATA TYPE VARCHAR(128);
