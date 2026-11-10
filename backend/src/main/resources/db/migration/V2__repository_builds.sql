ALTER TABLE builds DROP CONSTRAINT builds_recipe_check;
ALTER TABLE builds ADD CONSTRAINT builds_recipe_check CHECK (recipe IN ('JAVA_EXAMPLE_V1', 'REPOSITORY_V1'));
ALTER TABLE builds ADD COLUMN repository varchar(240);
ALTER TABLE builds ADD COLUMN source_ref varchar(200);
ALTER TABLE builds ADD COLUMN commit_sha varchar(40);
ALTER TABLE builds ADD COLUMN build_command varchar(2000);
ALTER TABLE builds ADD CONSTRAINT builds_source_check CHECK (
    (recipe = 'JAVA_EXAMPLE_V1' AND repository IS NULL AND source_ref IS NULL AND commit_sha IS NULL AND build_command IS NULL)
    OR (recipe = 'REPOSITORY_V1' AND repository IS NOT NULL AND source_ref IS NOT NULL AND commit_sha IS NOT NULL
        AND commit_sha ~ '^[0-9a-f]{40}$' AND build_command IS NOT NULL)
);
