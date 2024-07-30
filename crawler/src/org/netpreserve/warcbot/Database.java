package org.netpreserve.warcbot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.ArgumentFactory;
import org.jdbi.v3.core.argument.NullArgument;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.netpreserve.jwarc.WarcDigest;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.jdbi.v3.core.generic.GenericTypes.getErasedType;

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

    public Database(String jdbcUrl) throws IOException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setConnectionInitSql("PRAGMA synchronous = NORMAL; PRAGMA foreign_keys = ON;");
        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.registerColumnMapper(BareMediaType.class, stringColumnMapper(BareMediaType::new));
        jdbi.registerColumnMapper(Network.ResourceType.class, stringColumnMapper(Network.ResourceType::new));
        jdbi.registerColumnMapper(Url.class, stringColumnMapper(Url::new));
        jdbi.registerColumnMapper(WarcDigest.class, stringColumnMapper(WarcDigest::new));
        jdbi.registerArgument(stringArgument(BareMediaType.class, BareMediaType::value));
        jdbi.registerArgument(stringArgument(Network.ResourceType.class, Network.ResourceType::value));
        jdbi.registerArgument(stringArgument(Url.class, Url::toString));
        jdbi.registerArgument(stringArgument(WarcDigest.class, WarcDigest::prefixedBase32));
        init();
    }

    private static <T> ColumnMapper<T> stringColumnMapper(Function<String,T> constructor) {
        return (r, col, ctx) -> {
            String value = r.getString(col);
            return value == null ? null : constructor.apply(value);
        };
    }

    private static <T> ArgumentFactory.Preparable stringArgument(Class<T> clazz, Function<T, String> getter) {
        return (type, config) -> {
            if (!clazz.isAssignableFrom(getErasedType(type))) return Optional.empty();
            return Optional.of(value -> {
                if (value == null) return new NullArgument(Types.VARCHAR);
                return (pos, stmt, ctx) -> stmt.setString(pos, getter.apply((T) value));
            });
        };
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
