package org.netpreserve.warcbot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.netpreserve.jwarc.WarcDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Database implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(Database.class);
    private final Jdbi jdbi;
    private final HikariDataSource dataSource;

    public static Database newDatabaseInMemory() throws SQLException, IOException {
        return new Database("jdbc:sqlite::memory:");
    }

    public Database(Path path) throws SQLException, IOException {
        this("jdbc:sqlite:" + path);
    }

    public Database(String jdbcUrl) throws SQLException, IOException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.registerArgument(new AbstractArgumentFactory<Url>(Types.VARCHAR) {
            @Override
            protected Argument build(Url value, ConfigRegistry config) {
                return ((position, statement, ctx) -> statement.setString(position, value.toString()));
            }
        });
        jdbi.registerColumnMapper(Url.class, (ColumnMapper<Url>) (r, columnNumber, ctx) -> new Url(r.getString(columnNumber)));
        jdbi.registerArgument(new AbstractArgumentFactory<WarcDigest>(Types.VARCHAR) {
            @Override
            protected Argument build(WarcDigest value, ConfigRegistry config) {
                return ((position, statement, ctx) -> statement.setString(position, value.prefixedBase32()));
            }
        });
        jdbi.registerColumnMapper(WarcDigest.class, (r, columnNumber, ctx) -> new WarcDigest(r.getString(columnNumber)));
        init();
    }

    public void init() throws IOException {
        var regex = Pattern.compile("(?is)(CREATE\\s+TRIGGER\\b.+?END\\s*;)|((?:(?!CREATE\\s+TRIGGER\\b).)+?;)", Pattern.DOTALL);
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("schema.sql"), "Missing schema.sql")) {
            String schema = new String(stream.readAllBytes(), UTF_8);
            Matcher matcher = regex.matcher(schema);
            while (matcher.find()) {
                var sql = matcher.group().trim();
                if (sql.isBlank()) continue;
                jdbi.withHandle(handle -> handle.execute(sql));
            }
        }

        // release any leftover locks on startup after a crash
        frontier().unlockAllQueues();
        frontier().resetAllInProgressCandidates();
    }

    public FrontierDAO frontier() {
        return jdbi.onDemand(FrontierDAO.class);
    }

    public StorageDAO storage() {
        return jdbi.onDemand(StorageDAO.class);
    }

    public void close() {
        dataSource.close();
    }

    public RobotsTxtDAO robotsTxt() {
        return jdbi.onDemand(RobotsTxtDAO.class);
    }
}
