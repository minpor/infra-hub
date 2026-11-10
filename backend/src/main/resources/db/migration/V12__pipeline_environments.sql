ALTER TABLE pipelines ADD COLUMN environment varchar(4)
    CHECK (environment IN ('dev', 'prod'));

-- Only an explicit application target can supply an existing environment.
UPDATE pipelines p SET environment = a.configuration->>'target'
FROM applications a WHERE a.id = p.application_id
    AND a.configuration->>'target' IN ('dev', 'prod');
