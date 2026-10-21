-- Distributed ChatApp schema V3: Group Join Requests and Group Invitations

CREATE TABLE IF NOT EXISTS group_join_requests (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_group_join_request UNIQUE (group_id, user_id),
    CONSTRAINT chk_group_join_req_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT fk_gjreq_group FOREIGN KEY (group_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_gjreq_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS group_invitations (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    inviter_id UUID NOT NULL,
    invited_user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_group_invitation UNIQUE (group_id, invited_user_id),
    CONSTRAINT chk_group_inv_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT fk_ginv_group FOREIGN KEY (group_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_ginv_inviter FOREIGN KEY (inviter_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ginv_invited FOREIGN KEY (invited_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_gjreq_group_status ON group_join_requests (group_id, status);
CREATE INDEX IF NOT EXISTS idx_gjreq_user_status ON group_join_requests (user_id, status);
CREATE INDEX IF NOT EXISTS idx_ginv_invited_status ON group_invitations (invited_user_id, status);
CREATE INDEX IF NOT EXISTS idx_ginv_group_status ON group_invitations (group_id, status);
