CREATE TABLE mcp_tokens (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    permissions jsonb NOT NULL,
    project_ids jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE TABLE mcp_requests (
    token_id uuid NOT NULL REFERENCES mcp_tokens(id),
    request_id uuid NOT NULL,
    tool varchar(80) NOT NULL,
    arguments_hash char(64) NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (token_id,request_id)
);
ALTER TABLE pipelines ADD COLUMN release_requested boolean NOT NULL DEFAULT false;
ALTER TABLE deployments DROP CONSTRAINT deployments_status_check;
ALTER TABLE deployments ADD CONSTRAINT deployments_status_check CHECK (status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','CANCELLED','SUCCEEDED','FAILED','INTERRUPTED'));
DROP INDEX deployments_application_active;
CREATE UNIQUE INDEX deployments_application_active ON deployments(application_id) WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','INTERRUPTED');
DROP INDEX deployments_one_running;
CREATE UNIQUE INDEX deployments_one_running ON deployments((true)) WHERE status IN ('RUNNING','CANCEL_REQUESTED');
