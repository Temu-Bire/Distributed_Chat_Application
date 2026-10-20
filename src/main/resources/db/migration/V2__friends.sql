-- Distributed ChatApp schema V2: Friendships and Friend Requests

CREATE TABLE IF NOT EXISTS friendships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    friend_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_friendship UNIQUE (user_id, friend_id),
    CONSTRAINT fk_friendship_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friendship_friend FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS friend_requests (
    id UUID PRIMARY KEY,
    sender_id UUID NOT NULL,
    receiver_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_friend_req_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT fk_freq_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_freq_receiver FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_friendship_user ON friendships (user_id);
CREATE INDEX IF NOT EXISTS idx_friendship_friend ON friendships (friend_id);
CREATE INDEX IF NOT EXISTS idx_freq_receiver_status ON friend_requests (receiver_id, status);
CREATE INDEX IF NOT EXISTS idx_freq_sender_status ON friend_requests (sender_id, status);
