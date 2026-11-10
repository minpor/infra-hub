ALTER TABLE builds ADD COLUMN artifact_path text;
ALTER TABLE builds ADD COLUMN image_reference text;
ALTER TABLE builds ADD COLUMN artifact_sha256 text;
ALTER TABLE builds ADD COLUMN artifact_size bigint;
