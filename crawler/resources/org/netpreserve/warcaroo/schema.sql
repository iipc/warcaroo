PRAGMA journal_mode = WAL;


CREATE TABLE IF NOT EXISTS hosts
(
    id              INTEGER PRIMARY KEY,
    rhost           TEXT    NOT NULL UNIQUE,

    last_visit      INTEGER,
    next_visit      INTEGER,

    seeds           INTEGER NOT NULL DEFAULT 0,
    pending         INTEGER NOT NULL DEFAULT 0,
    failed          INTEGER NOT NULL DEFAULT 0,
    robots_excluded INTEGER NOT NULL DEFAULT 0,
    pages           INTEGER NOT NULL DEFAULT 0,
    resources       INTEGER NOT NULL DEFAULT 0,
    size            INTEGER NOT NULL DEFAULT 0,
    transferred     INTEGER NOT NULL DEFAULT 0,
    storage         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS domains
(
    id              INTEGER PRIMARY KEY,
    rhost           TEXT    NOT NULL UNIQUE,
    seeds           INTEGER NOT NULL DEFAULT 0,
    pending         INTEGER NOT NULL DEFAULT 0,
    failed          INTEGER NOT NULL DEFAULT 0,
    robots_excluded INTEGER NOT NULL DEFAULT 0,
    pages           INTEGER NOT NULL DEFAULT 0,
    resources       INTEGER NOT NULL DEFAULT 0,
    size            INTEGER NOT NULL DEFAULT 0,
    transferred     INTEGER NOT NULL DEFAULT 0,
    storage         INTEGER NOT NULL DEFAULT 0
);

create table if not exists frontier
(
    id         INTEGER PRIMARY KEY,
    host_id    INTEGER NOT NULL,
    domain_id  INTEGER NOT NULL,
    depth      INTEGER NOT NULL,
    url        TEXT    NOT NULL UNIQUE,
    state      TEXT    NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'IN_PROGRESS', 'CRAWLED', 'FAILED',
                                                                   'ROBOTS_EXCLUDED', 'OUT_OF_SCOPE')),
    via        TEXT,
    time_added INTEGER,

    FOREIGN KEY (host_id) references hosts (id),
    FOREIGN KEY (domain_id) references domains (id)
);

-- For FrontierDAO.nextUrlForHost()
CREATE INDEX IF NOT EXISTS frontier_host_state_depth_id ON frontier (host_id, state, depth, id);

create table if not exists resources
(
    id              INTEGER PRIMARY KEY,
    host_id         INTEGER NOT NULL,
    domain_id       INTEGER NOT NULL,
    method          TEXT             NOT NULL,
    url             TEXT             NOT NULL,
    date            INTEGER          NOT NULL,
    page_id         TEXT             NOT NULL,
    filename        TEXT             NOT NULL,
    response_uuid   TEXT,
    response_offset INTEGER          NOT NULL,
    response_length INTEGER          NOT NULL,
    request_length  INTEGER          NOT NULL,
    metadata_length INTEGER          NOT NULL,
    status          INTEGER          NOT NULL,
    redirect        TEXT,
    payload_type    TEXT,
    payload_size    INTEGER          NOT NULL,
    payload_digest  TEXT,
    fetch_time_ms   INTEGER          NOT NULL,
    ip_address      TEXT,
    type            TEXT,
    protocol        TEXT,
    transferred     INTEGER          NOT NULL,

    FOREIGN KEY (host_id) REFERENCES hosts (id),
    FOREIGN KEY (domain_id) REFERENCES domains (id)
);

CREATE INDEX IF NOT EXISTS resources_url_date ON resources (url, date);
CREATE INDEX IF NOT EXISTS resources_page_id ON resources (page_id);

create table if not exists pages
(
    id               INTEGER PRIMARY KEY NOT NULL,
    host_id          INTEGER             NOT NULL,
    domain_id        INTEGER             NOT NULL,
    url              TEXT                NOT NULL,
    date             INTEGER             NOT NULL,
    title            TEXT,
    error            TEXT,
    visit_time_ms    INTEGER,
    main_resource_id INTEGER,
    resources        INTEGER             NOT NULL DEFAULT 0,
    size             INTEGER             NOT NULL DEFAULT 0,
    FOREIGN KEY (host_id) REFERENCES hosts (id),
    FOREIGN KEY (domain_id) REFERENCES domains (id),
    FOREIGN KEY (main_resource_id) REFERENCES resources (id) ON DELETE SET NULL
);

create table if not exists robotstxt
(
    url          TEXT PRIMARY KEY NOT NULL,
    date         INTEGER          NOT NULL,
    last_checked INTEGER          NOT NULL,
    body         BLOB             NOT NULL
);

CREATE TABLE IF NOT EXISTS progress
(
    id         INTEGER NOT NULL PRIMARY KEY,
    date       INTEGER NULL,
    runtime    INTEGER NOT NULL,
    discovered INTEGER NOT NULL,
    pending    INTEGER NOT NULL,
    crawled    INTEGER NOT NULL,
    failed     INTEGER NOT NULL,
    resources  INTEGER NOT NULL,
    size       INTEGER NOT NULL
);

INSERT OR IGNORE INTO progress(id, date, runtime, discovered, pending, crawled, failed, resources, size)
VALUES (0, NULL, 0, 0, 0, 0, 0, 0, 0);