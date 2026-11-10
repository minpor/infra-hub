ALTER TABLE applications ADD COLUMN kind text NOT NULL DEFAULT 'STANDALONE' CHECK (kind IN ('STANDALONE','COMPOSE','ANDROID'));
ALTER TABLE applications ADD COLUMN configuration jsonb NOT NULL DEFAULT '{}';
ALTER TABLE applications ALTER COLUMN host_port DROP NOT NULL;
CREATE UNIQUE INDEX applications_compose_unique ON applications(server_id,(configuration->>'root'),(configuration->>'service')) WHERE kind='COMPOSE';
CREATE TABLE pipelines (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL UNIQUE REFERENCES applications(id),
    ref varchar(200) NOT NULL,
    command varchar(2000) NOT NULL,
    artifact_path varchar(300) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    last_sha varchar(40),
    last_fingerprint text,
    build_id uuid REFERENCES builds(id),
    deployment_id uuid REFERENCES deployments(id),
    message text,
    checked_at timestamptz
);
