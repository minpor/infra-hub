CREATE TABLE runner_settings (
    id boolean PRIMARY KEY DEFAULT true CHECK (id),
    cpus integer NOT NULL CHECK (cpus BETWEEN 1 AND 64),
    memory_gib integer NOT NULL CHECK (memory_gib BETWEEN 2 AND 1024),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO runner_settings (cpus, memory_gib) VALUES (6, 16);
