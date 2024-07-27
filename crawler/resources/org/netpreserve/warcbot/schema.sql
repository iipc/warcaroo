create table if not exists queues
(
    name         TEXT PRIMARY KEY NOT NULL,
    last_visited INTEGER,
    worker_id    INTEGER,
    next_visit   INTEGER                   DEFAULT 0
);

create table if not exists frontier
(
    queue      TEXT                                      NOT NULL,
    depth      INTEGER                                   NOT NULL,
    state      TEXT CHECK (state IN ('PENDING', 'IN_PROGRESS', 'CRAWLED', 'FAILED',
                                     'ROBOTS_EXCLUDED')) NOT NULL DEFAULT 'PENDING',
    url        TEXT                                      NOT NULL UNIQUE,
    rhost      TEXT NOT NULL,
    via        TEXT,
    time_added INTEGER,
    FOREIGN KEY (queue) references queues (name)
);

CREATE TABLE IF NOT EXISTS queue_state_counts
(
    queue TEXT,
    state TEXT,
    count INTEGER NOT NULL,
    PRIMARY KEY (queue, state)
);

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
    rhost           TEXT             NOT NULL,
    transferred     INTEGER          NOT NULL
);

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
);

create table if not exists robotstxt
(
    url          TEXT PRIMARY KEY NOT NULL,
    date         INTEGER          NOT NULL,
    last_checked INTEGER          NOT NULL,
    body         BLOB             NOT NULL
);

CREATE TABLE IF NOT EXISTS errors
(
    page_id    TEXT    NOT NULL,
    url        TEXT    NOT NULL,
    date       INTEGER NOT NULL,
    stacktrace TEXT    NOT NULL
);

DROP VIEW IF EXISTS hosts;

CREATE VIEW hosts AS
SELECT
    COALESCE(f.rhost, r.rhost) AS rhost,
    f.seeds AS seeds,
    f.pending AS pending,
    f.failed AS failed,
    f.robots_excluded AS robots_excluded,
    f.total AS total,
    r.pages AS pages,
    r.resources AS resources,
    r.size AS size,
    r.transferred AS transferred,
    r.storage AS storage
FROM
    (SELECT
         rhost,
         COUNT(*) FILTER (WHERE depth = 0) AS seeds,
         COUNT(*) FILTER (WHERE state = 'PENDING') AS pending,
         COUNT(*) FILTER (WHERE state = 'FAILED') AS failed,
         COUNT(*) FILTER (WHERE state = 'ROBOTS_EXCLUDED') AS robots_excluded,
         COUNT(*) AS total
     FROM frontier
     GROUP BY rhost) f
    FULL OUTER JOIN
    (SELECT
        rhost,
        (SELECT COUNT(*) FROM pages p WHERE p.rhost = resources.rhost) AS pages,
        COUNT(*) AS resources,
        SUM(payload_size) AS size,
        SUM(transferred) AS transferred,
        SUM(request_length) + SUM(response_length) AS storage
    FROM resources
    GROUP BY rhost) r
ON f.rhost = r.rhost;