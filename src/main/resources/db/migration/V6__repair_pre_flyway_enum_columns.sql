-- Repair columns that predate Flyway ownership.
--
-- Before Flyway became the sole schema owner, Hibernate's ddl-auto created these
-- tables, and it mapped @Enumerated(EnumType.STRING) columns to a *native H2 ENUM*
-- whose permitted value list is frozen at creation time. conversation_members.role
-- was created while MemberRole had only MEMBER and OWNER, so promoting a moderator
-- failed with "Value not permitted for column ('MEMBER', 'OWNER'): MODERATOR".
--
-- Flyway cannot fix this by itself: on an existing non-empty schema it baselines at
-- V1 and never replays it, so the columns V1 declares stay as they were. Hibernate's
-- ddl-auto: validate does not catch it either - it compares the column type, not the
-- permitted enum values. This migration widens them to what V1/V2/V3 always declared.
--
-- Every statement is a no-op shape on a database created from the migrations (the
-- column is already VARCHAR there), and ALTER COLUMN TYPE preserves stored values.

ALTER TABLE conversations          ALTER COLUMN type   TYPE VARCHAR(20);
ALTER TABLE conversation_members   ALTER COLUMN role   TYPE VARCHAR(20);
ALTER TABLE friend_requests        ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE group_join_requests    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE group_invitations      ALTER COLUMN status TYPE VARCHAR(20);

-- Hibernate-created columns carried no DEFAULT, unlike the migration definitions.
ALTER TABLE conversations        ALTER COLUMN type   SET DEFAULT 'DIRECT';
ALTER TABLE conversation_members ALTER COLUMN role   SET DEFAULT 'MEMBER';
ALTER TABLE friend_requests      ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE group_join_requests  ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE group_invitations    ALTER COLUMN status SET DEFAULT 'PENDING';
