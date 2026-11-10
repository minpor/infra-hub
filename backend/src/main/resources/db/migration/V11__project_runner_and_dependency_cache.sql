ALTER TABLE runner_settings ADD COLUMN dependency_cache_enabled boolean NOT NULL DEFAULT true;

CREATE TABLE project_runner_settings (
    project_id uuid PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    cpus integer NOT NULL CHECK (cpus BETWEEN 1 AND 64),
    memory_gib integer NOT NULL CHECK (memory_gib BETWEEN 2 AND 1024),
    dependency_cache_enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE builds ADD COLUMN runner_execution jsonb;
