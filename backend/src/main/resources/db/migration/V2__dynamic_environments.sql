ALTER TABLE bc_user DROP CONSTRAINT bc_user_user_group_check;
UPDATE bc_user SET user_group=CASE WHEN user_group='RHF-ADM' THEN 'ADM' ELSE 'USER' END;
ALTER TABLE bc_user ADD CONSTRAINT bc_user_role_check CHECK(user_group IN ('ADM','USER'));
ALTER TABLE bc_execution DROP CONSTRAINT bc_execution_actor_group_check;
ALTER TABLE bc_execution ALTER COLUMN actor_group TYPE varchar(80);

CREATE TABLE bc_environment (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    repository_path text NOT NULL,
    branch_patterns jsonb NOT NULL CHECK(jsonb_typeof(branch_patterns)='array'),
    permissions jsonb NOT NULL CHECK(jsonb_typeof(permissions)='array'),
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
