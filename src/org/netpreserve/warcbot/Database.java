package org.netpreserve.warcbot;

import com.fasterxml.uuid.impl.UUIDUtil;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netpreserve.jwarc.WarcDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Database implements AutoCloseable, StorageDB {
    private final Connection connection;

    public Database(Path path) throws SQLException, IOException {
        Files.createDirectories(path.getParent());
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        init();
    }

    public void init() throws SQLException, IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("schema.sql"), "Missing schema.sql");) {
            String schema = new String(stream.readAllBytes(), UTF_8);
            for (String sql : schema.split(";")) {
                sql = sql.trim();
                if (sql.isBlank()) continue;
//                System.err.println("SQL: " + sql);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.execute();
                }
            }
        }
    }

    public void close() throws SQLException {
        connection.close();
    }

    private long exec(@Language("SQLite") String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            return statement.executeLargeUpdate();
        }
    }

    public void frontierInsert(@NotNull String queue, int depth, @NotNull Url url, @Nullable Url via,
                               @NotNull Instant timeAdded, FrontierUrl.Status status) throws SQLException {
        exec("INSERT INTO frontier (queue, depth, url, via, time_added, status) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(url) DO NOTHING",
                queue, depth, url.toString(), via != null ? via.toString() : null, timeAdded.toEpochMilli(),
                status.name());
    }

    public @Nullable FrontierUrl frontierNext() throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM frontier WHERE status = 'PENDING' ORDER BY depth DESC LIMIT 1");
             var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) return null;
            return new FrontierUrl(
                    resultSet.getString("queue"),
                    new Url(resultSet.getString("url")),
                    resultSet.getInt("depth"),
                    Url.orNull(resultSet.getString("via")),
                    Instant.ofEpochMilli(resultSet.getLong("time_added")),
                    FrontierUrl.Status.valueOf(resultSet.getString("status")));
        }
    }

    public void frontierSetUrlStatus(@NotNull Url url, @NotNull FrontierUrl.Status status) throws SQLException {
        long rows = exec("UPDATE frontier SET status = ? WHERE url = ?", status.name(), url);
        if (rows == 0) throw new SQLException("URL not found in frontier: " + url);
    }

    public void queuesInsert(@NotNull String name) throws SQLException {
        exec("INSERT INTO queues (name) VALUES (?) ON CONFLICT(name) DO NOTHING", name);
    }

    public void insertResource(@NotNull UUID id, @NotNull UUID pageId, @NotNull String url, @NotNull Instant date,
                               long responseOffset, long responseLength, long requestLength, int status,
                               @Nullable String redirect,
                               String payloadType, long payloadSize, WarcDigest payloadDigest) throws SQLException {
        exec("""
                        INSERT INTO resources (id, page_id, url, date, response_offset, response_length, request_length,
                        status, redirect, payload_type, payload_size, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUIDUtil.asByteArray(id), pageId.toString(), url, date.toEpochMilli(), responseOffset, responseLength,
                requestLength, status, redirect, payloadType, payloadSize,
                payloadDigest == null ? null : payloadDigest.prefixedBase32());
    }

    public void insertPage(@NotNull UUID id, @NotNull Url url, @NotNull Instant date, String title) throws SQLException {
        exec("INSERT INTO pages (id, url, date, title) VALUES (?, ?, ?, ?)",
                UUIDUtil.asByteArray(id), url, date.toEpochMilli(), title);
    }

    public RobotsTxt robotsGet(String url) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM robotstxt WHERE url = ?")) {
            statement.setString(1, url);
            var resultSet = statement.executeQuery();
            if (!resultSet.next()) return null;
            return new RobotsTxt(
                    resultSet.getString("url"),
                    Instant.ofEpochMilli(resultSet.getLong("date")),
                    Instant.ofEpochMilli(resultSet.getLong("last_checked")),
                    resultSet.getBytes("body")
            );
        }
    }

    public void robotsUpsert(String url, Instant date, byte[] body) throws SQLException {
        exec("INSERT INTO robotstxt (url, date, last_checked, body) VALUES (?, ?, ?, ?) " +
             "ON CONFLICT (url) DO UPDATE SET date = excluded.date, " +
             " last_checked = excluded.last_checked, body = excluded.body",
                url, date.toEpochMilli(), date.toEpochMilli(), body);
    }

    public void robotsUpdateLastChecked(String url, Instant lastChecked) throws SQLException {
        exec("UPDATE robotstxt SET last_checked = ? WHERE url = ?", url);
    }

}
