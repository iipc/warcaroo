create table if not exists queues
(
    name         TEXT PRIMARY KEY NOT NULL,
    size         INTEGER          NOT NULL DEFAULT 0,
    last_visited INTEGER
) STRICT;

create table if not exists frontier
(
    queue      TEXT                                       NOT NULL,
    depth      INTEGER                                    NOT NULL,
    status     TEXT CHECK (status IN ('PENDING', 'IN_PROGRESS', 'CRAWLED', 'FAILED',
                                      'ROBOTS_EXCLUDED')) NOT NULL DEFAULT 'PENDING',
    url        TEXT                                       NOT NULL UNIQUE,
    via        TEXT,
    time_added INTEGER,
    FOREIGN KEY (queue) references queues (name)
) STRICT;

create table if not exists resources
(
    id              BLOB PRIMARY KEY NOT NULL CHECK ( length(id) == 16 ),
    url             TEXT             NOT NULL,
    date            INTEGER          NOT NULL,
    page_id         TEXT             NOT NULL,
    response_offset INTEGER          NOT NULL,
    response_length INTEGER          NOT NULL,
    request_length  INTEGER          NOT NULL,
    status          INTEGER          NOT NULL,
    redirect        TEXT,
    payload_type    TEXT,
    payload_size    INTEGER          NOT NULL,
    payload_digest  TEXT
) STRICT;

create table if not exists pages
(
    id    BLOB PRIMARY KEY NOT NULL CHECK ( length(id) == 16 ),
    url   TEXT             NOT NULL,
    date  INTEGER          NOT NULL,
    title TEXT
) STRICT;

create table if not exists robotstxt
(
    url          TEXT PRIMARY KEY NOT NULL,
    date         INTEGER          NOT NULL,
    last_checked INTEGER          NOT NULL,
    body         BLOB             NOT NULL
) STRICT;