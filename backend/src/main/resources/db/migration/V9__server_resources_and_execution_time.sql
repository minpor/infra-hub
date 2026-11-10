ALTER TABLE servers ADD COLUMN resources jsonb;
ALTER TABLE servers ADD COLUMN resources_observed_at timestamptz;
ALTER TABLE servers ADD COLUMN resources_attempted_at timestamptz;
ALTER TABLE servers ADD COLUMN resources_error text;

-- Existing execution start times cannot be reconstructed from queue timestamps.
ALTER TABLE deployments ADD COLUMN started_at timestamptz;
ALTER TABLE deployments ADD COLUMN attempt_started_at timestamptz;
ALTER TABLE deployments ADD COLUMN execution_millis bigint CHECK (execution_millis >= 0);
UPDATE deployments SET execution_millis = 0 WHERE status = 'QUEUED';
ALTER TABLE deployments ALTER COLUMN execution_millis SET DEFAULT 0;
