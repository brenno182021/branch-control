CREATE TABLE bc_lock (name varchar(40) PRIMARY KEY);
INSERT INTO bc_lock(name) VALUES ('users');

CREATE TABLE bc_user (
    id uuid PRIMARY KEY,
    username varchar(40) NOT NULL UNIQUE CHECK (username = lower(username)),
    name varchar(80) NOT NULL,
    password_hash varchar(60) NOT NULL CHECK (password_hash LIKE '$2%'),
    user_group varchar(16) NOT NULL CHECK (user_group IN ('RHF-TESTE','RHF-DEV','RHF-ADM')),
    enabled boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE bc_execution (
    id uuid PRIMARY KEY,
    actor_id varchar(64) NOT NULL,
    actor_username varchar(40) NOT NULL,
    actor_group varchar(16) NOT NULL CHECK (actor_group IN ('RHF-TESTE','RHF-DEV','RHF-ADM')),
    plan_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','COMPLETED','FAILED','CONFLICT','INTERRUPTED')),
    simulation boolean NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    snapshot jsonb NOT NULL,
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

CREATE TABLE bc_legacy_import (
    source varchar(64) PRIMARY KEY,
    imported_at timestamptz NOT NULL DEFAULT current_timestamp,
    users_count integer NOT NULL,
    executions_count integer NOT NULL
);
