create table if not exists queues
(
    name         TEXT PRIMARY KEY NOT NULL,
    last_visited INTEGER,
    worker_id    INTEGER,
    next_visit   INTEGER                   DEFAULT 0
) STRICT;

create table if not exists frontier
(
    queue      TEXT                                      NOT NULL,
    depth      INTEGER                                   NOT NULL,
    state      TEXT CHECK (state IN ('PENDING', 'IN_PROGRESS', 'CRAWLED', 'FAILED',
                                     'ROBOTS_EXCLUDED')) NOT NULL DEFAULT 'PENDING',
    url        TEXT                                      NOT NULL UNIQUE,
    via        TEXT,
    time_added INTEGER,
    FOREIGN KEY (queue) references queues (name)
) STRICT;

CREATE TABLE IF NOT EXISTS queue_state_counts
(
    queue TEXT,
    state TEXT,
    count INTEGER NOT NULL,
    PRIMARY KEY (queue, state)
) STRICT;

--region Triggers

CREATE TRIGGER IF NOT EXISTS after_frontier_insert
    AFTER INSERT
    ON frontier
BEGIN
    INSERT INTO queue_state_counts (queue, state, count)
    VALUES (NEW.queue, NEW.state, 1)
    ON CONFLICT(queue, state) DO UPDATE SET count = count + 1;
END;

CREATE TRIGGER IF NOT EXISTS after_frontier_update
    AFTER UPDATE OF queue, state
    ON frontier
BEGIN
    UPDATE queue_state_counts
    SET count = count - 1
    WHERE queue = OLD.queue
      AND state = OLD.state;

    DELETE FROM queue_state_counts WHERE queue = OLD.queue AND state = OLD.state AND count = 0;

    INSERT INTO queue_state_counts (queue, state, count)
    VALUES (NEW.queue, NEW.state, 1)
    ON CONFLICT(queue, state) DO UPDATE SET count = count + 1;
END;

CREATE TRIGGER IF NOT EXISTS after_frontier_delete
    AFTER DELETE
    ON frontier
BEGIN
    UPDATE queue_state_counts
    SET count = count - 1
    WHERE queue = OLD.queue
      AND state = OLD.state;

    DELETE FROM queue_state_counts WHERE queue = OLD.queue AND state = OLD.state AND count = 0;
END;

--endregion

create table if not exists resources
(
    id              TEXT PRIMARY KEY NOT NULL,
    method          TEXT             NOT NULL,
    url             TEXT             NOT NULL,
    date            INTEGER          NOT NULL,
    page_id         TEXT             NOT NULL,
    filename        TEXT             NOT NULL,
    response_offset INTEGER          NOT NULL,
    response_length INTEGER          NOT NULL,
    request_length  INTEGER          NOT NULL,
    status          INTEGER          NOT NULL,
    redirect        TEXT,
    payload_type    TEXT,
    payload_size    INTEGER          NOT NULL,
    payload_digest  TEXT,
    fetch_time_ms   INTEGER          NOT NULL,
    ip_address      TEXT,
    type            TEXT,
    protocol        TEXT,
    rhost           TEXT             NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS resources_url_date ON resources (url, date);
CREATE INDEX IF NOT EXISTS resources_page_id ON resources (page_id);

create table if not exists pages
(
    id            TEXT PRIMARY KEY NOT NULL,
    url           TEXT             NOT NULL,
    date          INTEGER          NOT NULL,
    title         TEXT,
    visit_time_ms INTEGER          NOT NULL,
    rhost         TEXT             NOT NULL
) STRICT;

create table if not exists robotstxt
(
    url          TEXT PRIMARY KEY NOT NULL,
    date         INTEGER          NOT NULL,
    last_checked INTEGER          NOT NULL,
    body         BLOB             NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS errors
(
    page_id    TEXT    NOT NULL,
    url        TEXT    NOT NULL,
    date       INTEGER NOT NULL,
    stacktrace TEXT    NOT NULL
) STRICT;