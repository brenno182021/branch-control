-- First installation only. Apply manually to an empty database; subsequent changes are manual DBA operations.

BEGIN;

CREATE TABLE bc_lock (name varchar(40) PRIMARY KEY);
INSERT INTO bc_lock(name) VALUES ('users');

CREATE TABLE bc_user (
    id uuid PRIMARY KEY,
    username varchar(40) NOT NULL UNIQUE CHECK (username = lower(username)),
    name varchar(80) NOT NULL,
    password_hash varchar(60) NOT NULL CHECK (password_hash LIKE '$2%'),
    user_group varchar(16) NOT NULL CHECK (user_group IN ('ADM','USER')),
    enabled boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE bc_execution (
    id uuid PRIMARY KEY,
    actor_id varchar(64) NOT NULL,
    actor_username varchar(40) NOT NULL,
    actor_group varchar(80) NOT NULL,
    plan_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','COMPLETED','FAILED','CONFLICT','INTERRUPTED')),
    simulation boolean NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    repository_path text NOT NULL,
    source_branch text NOT NULL,
    target_branch text NOT NULL,
    source_head text NOT NULL,
    target_head text NOT NULL,
    cutoff text NOT NULL,
    zone_id text NOT NULL,
    plan_created_at text NOT NULL,
    environment_id text,
    environment_revision bigint NOT NULL,
    run_repository_path text NOT NULL,
    run_source_branch text NOT NULL,
    run_target_branch text NOT NULL,
    run_started_at text NOT NULL,
    run_finished_at text NOT NULL,
    log_path text,
    run_environment_id text,
    environment_name text,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX bc_execution_actor_started_idx ON bc_execution(actor_id, started_at DESC);

CREATE INDEX bc_execution_group_started_idx ON bc_execution(actor_group, started_at DESC);

CREATE TABLE bc_execution_log (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id uuid NOT NULL REFERENCES bc_execution(id),
    line text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX bc_execution_log_execution_idx ON bc_execution_log(execution_id, sequence);

CREATE TABLE bc_environment (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    repository_path text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL CHECK(revision>0),
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE UNIQUE INDEX bc_environment_name_unique ON bc_environment(lower(name));

CREATE TABLE bc_environment_member (
    environment_id uuid NOT NULL REFERENCES bc_environment(id),
    user_id uuid NOT NULL REFERENCES bc_user(id),
    PRIMARY KEY(environment_id,user_id)
);

CREATE INDEX bc_environment_member_user_idx ON bc_environment_member(user_id);

CREATE TABLE bc_environment_branch_pattern (
    environment_id uuid NOT NULL REFERENCES bc_environment(id),
    position integer NOT NULL CHECK(position >= 0),
    pattern text NOT NULL,
    PRIMARY KEY(environment_id,position)
);

CREATE TABLE bc_environment_permission (
    environment_id uuid NOT NULL REFERENCES bc_environment(id),
    permission varchar(16) NOT NULL CHECK(permission IN ('REPORTS','PREPARE','SIMULATE','APPLY','FETCH','SWITCH','APPROVE_USERS')),
    PRIMARY KEY(environment_id,permission)
);

CREATE TABLE bc_execution_candidate (
    execution_id uuid NOT NULL REFERENCES bc_execution(id),
    position integer NOT NULL CHECK(position >= 0),
    hash text NOT NULL,
    commit_date text NOT NULL,
    message text NOT NULL,
    PRIMARY KEY(execution_id,position)
);

CREATE TABLE bc_execution_selected (
    execution_id uuid NOT NULL REFERENCES bc_execution(id),
    position integer NOT NULL CHECK(position >= 0),
    hash text NOT NULL,
    message text NOT NULL,
    state text NOT NULL,
    new_hash text NOT NULL,
    PRIMARY KEY(execution_id,position)
);

CREATE TABLE bc_access_request (
    id uuid PRIMARY KEY,
    username varchar(40) NOT NULL CHECK (username = lower(username)),
    name varchar(80) NOT NULL,
    password_hash varchar(60),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    reviewer_id uuid,
    reviewer_username varchar(40),
    environment_id uuid,
    environment_name varchar(80),
    rejection_reason varchar(500),
    approved_user_id uuid,
    CHECK ((status='PENDING' AND password_hash IS NOT NULL AND password_hash ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$' AND reviewed_at IS NULL AND reviewer_id IS NULL AND reviewer_username IS NULL AND environment_id IS NULL AND environment_name IS NULL AND rejection_reason IS NULL AND approved_user_id IS NULL)
        OR (status='APPROVED' AND password_hash IS NULL AND reviewed_at IS NOT NULL AND reviewer_id IS NOT NULL AND reviewer_username IS NOT NULL AND environment_id IS NOT NULL AND environment_name IS NOT NULL AND approved_user_id IS NOT NULL AND rejection_reason IS NULL)
        OR (status='REJECTED' AND password_hash IS NULL AND reviewed_at IS NOT NULL AND reviewer_id IS NOT NULL AND reviewer_username IS NOT NULL AND environment_id IS NULL AND environment_name IS NULL AND approved_user_id IS NULL))
);
CREATE UNIQUE INDEX bc_access_request_pending_username_unique ON bc_access_request(username) WHERE status='PENDING';
CREATE INDEX bc_access_request_pending_created_idx ON bc_access_request(created_at,id) WHERE status='PENDING';

COMMIT;
