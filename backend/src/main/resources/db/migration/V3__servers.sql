CREATE TABLE servers (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL UNIQUE,
    host varchar(253) NOT NULL,
    port integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    username varchar(64) NOT NULL,
    public_key text NOT NULL,
    host_key text,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    message text,
    checked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (host, port, username)
);
