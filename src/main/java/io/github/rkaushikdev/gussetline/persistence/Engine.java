package io.github.rkaushikdev.gussetline.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.gussetline.config.Settings;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * One database: its connection pool, its schema, and the entity manager factory every unit of work
 * is opened from.
 *
 * <p>An engine is made once per process from the settings and nothing else, so a command line run
 * starts without a framework, and the service hands the same engine to its routes. Nothing connects
 * until something is asked, and the entity manager factory is built the first time it is asked for,
 * when it checks the mapping against the schema. A database nobody has migrated can still be
 * migrated and dropped.
 *
 * <p>H2 enforces foreign keys on every connection, so the cascades in the schema mean what they
 * say without anything being switched on: a coupon cannot outlive its ticket.
 *
 * <p>An in memory database has to be one database for the whole pool. H2 gives every connection to
 * an unnamed one a private database of its own, so a schema built on the first connection would be
 * invisible to the second, and anything that opened a session on another connection would report
 * that no table exists: the schema there, and the reader looking at a different database of the same
 * name. So an unnamed one is given a name of its own, private to the engine and gone when the engine
 * is closed, and a named one is kept for as long as the process runs; see {@link #jdbcUrl(String)}.
 */
public final class Engine implements AutoCloseable {

    /** Where the entities and their column converters live. */
    public static final String MAPPED_PACKAGE = "io.github.rkaushikdev.gussetline.persistence";

    /** Where the migrations live, one script per revision of the schema. */
    public static final String MIGRATIONS = "classpath:db/migration";

    /** The table the migrations keep their own history in. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    private static final String IN_MEMORY = "jdbc:h2:mem:";
    private static final String KEEP_OPEN = "DB_CLOSE_DELAY=-1";

    private final Settings settings;
    private final String jdbcUrl;
    private final boolean echo;
    private final HikariDataSource pool;
    private LocalContainerEntityManagerFactoryBean factoryBean;

    private Engine(Settings settings, boolean echo) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.echo = echo;
        this.jdbcUrl = jdbcUrl(settings.databaseUrl());
        this.pool = pool(jdbcUrl);
    }

    /** The engine for the database the settings name, quiet about the statements it runs. */
    public static Engine make(Settings settings) {
        return make(settings, false);
    }

    /**
     * The engine for the database the settings name.
     *
     * @param echo whether every statement is printed as it runs
     */
    public static Engine make(Settings settings, boolean echo) {
        return new Engine(settings, echo);
    }

    /**
     * The URL as the driver is given it.
     *
     * <p>An unnamed in memory database is given a name of its own, a different one on every call, so
     * the engine made from it has a private database that all of its connections share and that goes
     * when the last of them is closed. A named one is kept open for as long as the process runs,
     * unless the URL says otherwise, so a pool that lets every connection go has not thrown the
     * schema away by the time it takes the next one. Anything else is left as configured.
     */
    public static String jdbcUrl(String configured) {
        if (!configured.startsWith(IN_MEMORY)) {
            return configured;
        }
        String rest = configured.substring(IN_MEMORY.length());
        if (rest.isEmpty() || rest.startsWith(";")) {
            return IN_MEMORY + "gussetline-" + UUID.randomUUID() + rest;
        }
        if (!rest.toUpperCase(Locale.ROOT).contains("DB_CLOSE_DELAY")) {
            return configured + ";" + KEEP_OPEN;
        }
        return configured;
    }

    /**
     * A pool for {@code jdbcUrl}, sized for one process and connecting only when first asked. A pool
     * over an in memory database never retires a connection for its age, since the last connection
     * to a private one going takes the database with it.
     */
    public static HikariDataSource pool(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setPoolName("gussetline");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setInitializationFailTimeout(-1);
        // A database that will not answer is reported within seconds, not after half a minute.
        config.setConnectionTimeout(3_000);
        if (jdbcUrl.startsWith(IN_MEMORY)) {
            config.setMaxLifetime(0);
        }
        return new HikariDataSource(config);
    }

    /**
     * The factory bean every part of the process opens entity managers from, built over a data
     * source without a Spring context.
     *
     * @param ddl what is done to the schema when the factory is built: {@code validate} to check the
     *     mapping against the migrated schema, or {@code create} to build a schema straight from the
     *     mapping for comparison
     */
    public static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean(
            DataSource dataSource, boolean echo, String ddl) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan(MAPPED_PACKAGE);
        bean.setPersistenceUnitName("gussetline");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hibernate.hbm2ddl.auto", ddl);
        properties.put("hibernate.show_sql", String.valueOf(echo));
        // Columns otherwise come out ordered by the size of their type, composite keys included, and
        // a schema built from the mapping stops matching the order the migrations declare.
        properties.put("hibernate.column_ordering_strategy", "legacy");
        // Wall clock times go to the driver as they are. Passed through the process's own time zone
        // instead, a UTC stamp that falls in that zone's spring forward gap moves by an hour.
        properties.put("hibernate.type.java_time_use_direct_jdbc", "true");
        properties.put("hibernate.default_batch_fetch_size", "64");
        bean.setJpaPropertyMap(properties);
        return bean;
    }

    /** The settings this engine was made from. */
    public Settings settings() {
        return settings;
    }

    /** The URL the settings name, as configured. */
    public String url() {
        return settings.databaseUrl();
    }

    /** The URL the driver is given: for an unnamed in memory database, the one naming its private database. */
    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** Where connections come from. */
    public DataSource dataSource() {
        return pool;
    }

    /** The factory, built and checked against the schema the first time it is asked for. */
    public synchronized EntityManagerFactory entityManagerFactory() {
        if (factoryBean == null) {
            LocalContainerEntityManagerFactoryBean bean = entityManagerFactoryBean(pool, echo, "validate");
            bean.afterPropertiesSet();
            factoryBean = bean;
        }
        return factoryBean.getObject();
    }

    /**
     * Build the whole schema in a database that has none, or bring an older one up to date. Running it
     * on a database that is already up to date does nothing.
     */
    public void createSchema() {
        flyway().migrate();
    }

    /**
     * Take the whole schema out, the migrations' own history included, and everything in it with it.
     * The factory is let go first; asking for it again checks the mapping against whatever schema
     * there is by then.
     */
    public void dropSchema() {
        closeFactory();
        flyway().clean();
    }

    /** Every table in the database, the migrations' history among them, lower case and sorted. */
    public List<String> tableNames() {
        try (Connection connection = pool.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            TreeSet<String> names = new TreeSet<>();
            try (ResultSet tables = metadata.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String schema = tables.getString("TABLE_SCHEM");
                    if (!"INFORMATION_SCHEMA".equalsIgnoreCase(schema)) {
                        names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            return List.copyOf(names);
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not list the tables in " + url(), trouble);
        }
    }

    /**
     * Let go of the factory and every connection. A private in memory database goes with them.
     * Closing twice does nothing more.
     */
    @Override
    public synchronized void close() {
        closeFactory();
        pool.close();
    }

    private synchronized void closeFactory() {
        if (factoryBean != null) {
            factoryBean.destroy();
            factoryBean = null;
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(pool)
                .locations(MIGRATIONS)
                .validateMigrationNaming(true)
                .cleanDisabled(false)
                .load();
    }
}
