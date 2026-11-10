ALTER TABLE pipelines DROP CONSTRAINT pipelines_application_id_key;
ALTER TABLE pipelines ADD COLUMN config_path varchar(300);
CREATE UNIQUE INDEX pipelines_legacy_binding ON pipelines(application_id) WHERE config_path IS NULL;
CREATE UNIQUE INDEX pipelines_yaml_binding ON pipelines(application_id,ref,config_path) WHERE config_path IS NOT NULL;
ALTER TABLE pipelines ALTER COLUMN artifact_path DROP NOT NULL;
ALTER TABLE builds ALTER COLUMN build_command TYPE text;
ALTER TABLE builds ADD COLUMN yaml_recipe jsonb;
ALTER TABLE deployments ADD COLUMN yaml_execution jsonb;
ALTER TABLE applications DROP CONSTRAINT applications_kind_check;
ALTER TABLE applications ADD CONSTRAINT applications_kind_check CHECK (kind IN ('STANDALONE','COMPOSE','ANDROID','SCRIPT'));

CREATE TABLE pipeline_release_requests (
    request_id uuid PRIMARY KEY,
    pipeline_id uuid NOT NULL REFERENCES pipelines(id),
    build_id uuid NOT NULL REFERENCES builds(id)
);
