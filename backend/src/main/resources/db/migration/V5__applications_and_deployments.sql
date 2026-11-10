CREATE TABLE applications (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    server_id uuid NOT NULL REFERENCES servers(id),
    name varchar(80) NOT NULL,
    host_port integer NOT NULL CHECK (host_port BETWEEN 1024 AND 65535),
    container_port integer NOT NULL CHECK (container_port BETWEEN 1 AND 65535),
    health_path varchar(200) NOT NULL,
    environment jsonb NOT NULL DEFAULT '{}',
    current_deployment uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (server_id, host_port)
);
CREATE TABLE deployments (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES applications(id),
    build_id uuid NOT NULL REFERENCES builds(id),
    image_reference text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
    lease_owner uuid,
    lease_until timestamptz,
    message text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
ALTER TABLE applications ADD CONSTRAINT applications_current_deployment_fk FOREIGN KEY (current_deployment) REFERENCES deployments(id);
CREATE UNIQUE INDEX deployments_application_active ON deployments(application_id) WHERE status IN ('QUEUED','RUNNING','INTERRUPTED');
CREATE UNIQUE INDEX deployments_one_running ON deployments((true)) WHERE status = 'RUNNING';
CREATE TABLE deployment_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deployment_id uuid NOT NULL REFERENCES deployments(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    text varchar(8192) NOT NULL
);
CREATE INDEX deployment_logs_cursor ON deployment_logs(deployment_id, id);
