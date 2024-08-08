package org.netpreserve.warcbot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.ArgumentFactory;
import org.jdbi.v3.core.argument.NullArgument;
import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.sqlobject.CreateSqlObject;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.locator.UseClasspathSqlLocator;
import org.jdbi.v3.sqlobject.statement.SqlScript;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.netpreserve.jwarc.WarcDigest;
import org.netpreserve.warcbot.cdp.domains.Network;
import org.netpreserve.warcbot.util.BareMediaType;
import org.netpreserve.warcbot.util.Url;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static org.jdbi.v3.core.generic.GenericTypes.getErasedType;

public interface Database extends AutoCloseable, Transactional<Database> {
    static Database newDatabaseInMemory() throws IOException {
        return open("jdbc:sqlite::memory:");
    }

    static Database open(Path path) throws IOException {
        return open("jdbc:sqlite:" + path);
    }

    static Database open(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setConnectionInitSql("PRAGMA synchronous = NORMAL; PRAGMA foreign_keys = ON;");
        config.setMaximumPoolSize(1);
        var dataSource = new HikariDataSource(config);
        var jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.registerColumnMapper(BareMediaType.class, stringColumnMapper(BareMediaType::new));
        jdbi.registerColumnMapper(Network.ResourceType.class, stringColumnMapper(Network.ResourceType::new));
        jdbi.registerColumnMapper(Url.class, stringColumnMapper(Url::new));
        jdbi.registerColumnMapper(WarcDigest.class, stringColumnMapper(WarcDigest::new));
        jdbi.registerArgument(stringArgument(BareMediaType.class, BareMediaType::value));
        jdbi.registerArgument(stringArgument(Network.ResourceType.class, Network.ResourceType::value));
        jdbi.registerArgument(stringArgument(Url.class, Url::toString));
        jdbi.registerArgument(stringArgument(WarcDigest.class, WarcDigest::prefixedBase32));
        jdbi.getConfig(DataSourceHolder.class).dataSource = dataSource;
        Database db = jdbi.onDemand(Database.class);
        db.init();
        return db;
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

    default void init() {
        // we can't use @SqlScript because we need to use executeAsSeparateStatements() on sqlite
        try (var stream = Objects.requireNonNull(Database.class.getResourceAsStream("schema.sql"), "missing schema.sql")) {
            var schema = new String(stream.readAllBytes());
            useHandle(handle -> handle.createScript(schema).executeAsSeparateStatements());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @CreateSqlObject
    DomainDAO domains();

    @CreateSqlObject
    FrontierDAO frontier();

    @CreateSqlObject
    HostDAO hosts();

    @CreateSqlObject
    PageDAO pages();

    @CreateSqlObject
    ResourceDAO resources();

    @CreateSqlObject
    RobotsTxtDAO robotsTxt();

    default HikariDataSource dataSource() {
        return withHandle(handle -> handle.getConfig(DataSourceHolder.class).dataSource);
    }

    default void close() {
        dataSource().close();
    }

    class DataSourceHolder implements JdbiConfig<DataSourceHolder> {
        private HikariDataSource dataSource;

        public DataSourceHolder() {
        }

        @Override
        public DataSourceHolder createCopy() {
            var copy = new DataSourceHolder();
            copy.dataSource = dataSource;
            return copy;
        }
    }
}
