CREATE TABLE projects (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL CHECK (length(trim(name)) > 0),
    repository_url varchar(240),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX projects_name_unique ON projects (lower(name));

CREATE TABLE builds (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    request_id uuid NOT NULL UNIQUE,
    recipe varchar(40) NOT NULL CHECK (recipe = 'JAVA_EXAMPLE_V1'),
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    worker_id uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    finished_at timestamptz,
    message varchar(500)
);
CREATE INDEX builds_queue ON builds (created_at, id) WHERE status = 'QUEUED';
CREATE UNIQUE INDEX builds_one_active ON builds ((true)) WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');
CREATE TABLE build_steps (
    build_id uuid NOT NULL REFERENCES builds(id),
    position integer NOT NULL,
    name varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    started_at timestamptz,
    finished_at timestamptz,
    exit_code integer,
    PRIMARY KEY (build_id, position)
);
CREATE TABLE build_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id uuid NOT NULL REFERENCES builds(id),
    step_position integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    text varchar(8192) NOT NULL
);
CREATE INDEX build_logs_cursor ON build_logs (build_id, id);
