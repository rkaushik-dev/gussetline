package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.gussetline.config.Settings;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * The migrations have to build the schema the mapping describes.
 *
 * <p>A migration written by hand is a draft. It misses a type change, it invents a constraint name,
 * and it silently leaves out a table somebody added to the mapping. So the suite runs the
 * migrations against an empty database, builds a second schema straight from the mapping, and
 * compares the two table by table, and any difference is a failure with the difference named.
 *
 * <p>This is the check that catches the commit where somebody adds a column and forgets the
 * migration, which is otherwise found in production.
 */
class MigrationsTest {

    private static final Pattern SCRIPT = Pattern.compile("V(\\d+)__(\\w+)\\.sql");

    private static Engine migrated;
    private static HikariDataSource builtPool;
    private static LocalContainerEntityManagerFactoryBean builder;
    private static Set<String> declared;

    @BeforeAll
    static void buildBothSchemas() {
        migrated = Engine.make(new Settings().withDatabase("jdbc:h2:mem:migrations-migrated"));
        migrated.createSchema();

        builtPool = Engine.pool(Engine.jdbcUrl("jdbc:h2:mem:migrations-built"));
        builder = Engine.entityManagerFactoryBean(builtPool, false, "create");
        builder.afterPropertiesSet();
        declared = new TreeSet<>();
        for (EntityType<?> entity : builder.getObject().getMetamodel().getEntities()) {
            declared.add(entity.getJavaType().getAnnotation(Table.class).name());
        }
    }

    @AfterAll
    static void closeBoth() {
        builder.destroy();
        builtPool.close();
        migrated.close();
    }

    /** Every difference between two schemas, aspect by aspect, for the tables the mapping declares. */
    private static List<String> differences(DataSource left, DataSource right) {
        Catalog one = new Catalog(left);
        Catalog other = new Catalog(right);
        List<String> found = new ArrayList<>();
        for (String table : declared) {
            compare(found, table, "columns", one.columns(table), other.columns(table));
            compare(found, table, "types", one.types(table), other.types(table));
            compare(found, table, "primary key", one.primaryKey(table), other.primaryKey(table));
            compare(found, table, "unique constraints", one.uniques(table), other.uniques(table));
            compare(found, table, "foreign keys", one.foreignKeys(table), other.foreignKeys(table));
            compare(found, table, "indexes", one.indexes(table), other.indexes(table));
        }
        return found;
    }

    private static void compare(List<String> found, String table, String aspect, Set<String> left, Set<String> right) {
        Set<String> onlyLeft = new TreeSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new TreeSet<>(right);
        onlyRight.removeAll(left);
        if (!onlyLeft.isEmpty() || !onlyRight.isEmpty()) {
            found.add(table + " " + aspect + ": migrated only " + onlyLeft + ", mapped only " + onlyRight);
        }
    }

    /** How many lines each kind of reading finds across every declared table. */
    private static long readings(DataSource source, BiFunction<Catalog, String, Set<String>> reading) {
        Catalog catalog = new Catalog(source);
        return declared.stream()
                .mapToLong(table -> reading.apply(catalog, table).size())
                .sum();
    }

    /** The revision numbers, read from the script names on the class path, in the order found. */
    private static List<Integer> revisions() {
        List<Integer> numbers = new ArrayList<>();
        try {
            for (Resource script :
                    new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*")) {
                Matcher named = SCRIPT.matcher(String.valueOf(script.getFilename()));
                assertThat(named.matches())
                        .as("%s is named V<number>__<name>.sql", script.getFilename())
                        .isTrue();
                numbers.add(Integer.parseInt(named.group(1)));
            }
        } catch (IOException trouble) {
            throw new IllegalStateException("could not list the migrations", trouble);
        }
        assertThat(numbers).isNotEmpty();
        return numbers;
    }

    /** The revisions nothing comes after: one, unless the history has branched. */
    private static List<Integer> heads(List<Integer> numbers) {
        int last = Collections.max(numbers);
        return numbers.stream().filter(number -> number == last).toList();
    }

    /** The numbers missing between the first revision and the last. */
    private static List<Integer> gaps(List<Integer> numbers) {
        Set<Integer> present = new HashSet<>(numbers);
        List<Integer> missing = new ArrayList<>();
        for (int number = 1; number <= Collections.max(numbers); number++) {
            if (!present.contains(number)) {
                missing.add(number);
            }
        }
        return missing;
    }

    /** The numbers more than one revision claims. */
    private static Set<Integer> shared(List<Integer> numbers) {
        Set<Integer> seen = new HashSet<>();
        Set<Integer> twice = new TreeSet<>();
        for (int number : numbers) {
            if (!seen.add(number)) {
                twice.add(number);
            }
        }
        return twice;
    }

    /** The revision numbers in the order the migrations applied them. */
    private static List<Integer> applied(DataSource source) {
        List<Integer> versions = new ArrayList<>();
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select \"version\" from \"" + Engine.HISTORY_TABLE
                        + "\" where \"success\" and \"version\" is not null order by \"installed_rank\"")) {
            while (rows.next()) {
                versions.add(Integer.parseInt(rows.getString(1)));
            }
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not read the migration history", trouble);
        }
        return versions;
    }

    private static boolean inOrder(List<Integer> numbers) {
        return numbers.equals(numbers.stream().sorted().toList());
    }

    private static void execute(DataSource source, String... statements) {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not plant the change", trouble);
        }
    }

    @Test
    void theMigratedSchemaMatchesTheModels() {
        assertThat(differences(migrated.dataSource(), builtPool)).isEmpty();
        // The mapping accepts the migrated schema as it stands.
        assertThat(migrated.entityManagerFactory().getMetamodel().getEntities()).hasSize(declared.size());

        // The comparison passes when both sides read nothing, so each reading has to find
        // something on both sides, and a change planted on one side has to be seen.
        List<BiFunction<Catalog, String, Set<String>>> kinds = List.of(
                Catalog::columns,
                Catalog::types,
                Catalog::primaryKey,
                Catalog::uniques,
                Catalog::foreignKeys,
                Catalog::indexes);
        for (BiFunction<Catalog, String, Set<String>> kind : kinds) {
            assertThat(readings(migrated.dataSource(), kind)).isPositive();
            assertThat(readings(builtPool, kind)).isPositive();
        }
        try (Engine planted = Engine.make(new Settings().withDatabase("jdbc:h2:mem:"))) {
            planted.createSchema();
            execute(
                    planted.dataSource(),
                    "ALTER TABLE airports ADD COLUMN planted INTEGER",
                    "ALTER TABLE carriers ALTER COLUMN name SET DATA TYPE VARCHAR(65)",
                    "CREATE INDEX ix_quotes_planted ON quotes (carrier)",
                    "ALTER TABLE rates ADD CONSTRAINT uq_rates_planted UNIQUE (source, currency)",
                    "ALTER TABLE coupons DROP CONSTRAINT fk_coupons_ticket_id_tickets");
            assertThat(differences(planted.dataSource(), builtPool))
                    .anyMatch(line -> line.startsWith("airports columns"))
                    .anyMatch(line -> line.startsWith("carriers types"))
                    .anyMatch(line -> line.startsWith("quotes indexes"))
                    .anyMatch(line -> line.startsWith("rates unique constraints"))
                    .anyMatch(line -> line.startsWith("coupons foreign keys"))
                    .noneMatch(line -> line.startsWith("tickets"));
        }
    }

    @Test
    void everyTableInTheMetadataWasCreated() {
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(migrated.tableNames());
        assertThat(missing).isEmpty();
    }

    @Test
    void theMigrationHistoryTableIsThere() {
        assertThat(migrated.tableNames()).contains(Engine.HISTORY_TABLE);
    }

    @Test
    void aDowngradeTakesEveryTableBackOut(@TempDir Path directory) {
        try (Engine engine =
                Engine.make(new Settings().withDatabase("jdbc:h2:file:" + directory.resolve("roundtrip")))) {
            engine.createSchema();
            assertThat(engine.tableNames()).containsAll(declared);
            engine.dropSchema();
            Set<String> left = new TreeSet<>(engine.tableNames());
            left.remove(Engine.HISTORY_TABLE);
            assertThat(left).isEmpty();
        }
    }

    @Test
    void theMigrationCanBeRunTwiceWithoutComplaint(@TempDir Path directory) {
        try (Engine engine = Engine.make(new Settings().withDatabase("jdbc:h2:file:" + directory.resolve("twice")))) {
            engine.createSchema();
            engine.createSchema();
            assertThat(engine.tableNames()).contains("fares");
        }
    }

    @Test
    void theNamedConstraintsSurviveTheMigration() {
        assertThat(new Catalog(migrated.dataSource()).indexes("observations"))
                .anyMatch(line -> line.startsWith("ix_observations_series_seen |"))
                .anyMatch(line -> line.startsWith("ix_observations_market |"));
    }

    @Test
    void theForeignKeysAreCreatedWithTheirNames() {
        List<String> keys = List.copyOf(new Catalog(migrated.dataSource()).foreignKeys("coupons"));
        assertThat(keys).isNotEmpty();
        String[] first = keys.get(0).split(" \\| ");
        assertThat(first[0]).isEqualTo("fk_coupons_ticket_id_tickets");
        assertThat(first[2]).isEqualTo("tickets");
    }

    /** A branch in the history is a merge nobody will notice until deploy. */
    @Test
    void everyRevisionHasAParentExceptTheFirst() {
        assertThat(heads(revisions())).as("the history has branched").hasSize(1);
        assertThat(heads(List.of(1, 2, 3, 3))).hasSize(2);
    }

    @Test
    void theRevisionsRunInTheOrderTheyAreNumbered() {
        List<Integer> ran = applied(migrated.dataSource());
        assertThat(ran).hasSameSizeAs(revisions());
        assertThat(inOrder(ran)).isTrue();
        assertThat(inOrder(List.of(1, 3, 2))).isFalse();
    }

    /**
     * There is no undoing one revision at a time: the way back is to take the whole schema out. So
     * every revision is applied in turn to an empty database, and taking the schema out afterwards
     * has to leave nothing behind, which a revision that put something outside the schema would.
     */
    @Test
    void everyRevisionSaysHowToGoBack() {
        for (int revision : revisions()) {
            try (Engine engine = Engine.make(new Settings().withDatabase("jdbc:h2:mem:"))) {
                Flyway.configure()
                        .dataSource(engine.dataSource())
                        .locations(Engine.MIGRATIONS)
                        .target(String.valueOf(revision))
                        .load()
                        .migrate();
                assertThat(engine.tableNames()).as("revision %d", revision).contains("airports");
                engine.dropSchema();
                assertThat(engine.tableNames()).as("revision %d", revision).isEmpty();
            }
        }
    }

    /** One line back to the beginning. A missing revision is a silent skip. */
    @Test
    void theChainReachesBaseFromTheHeadWithoutAGap() {
        List<Integer> numbers = revisions();
        assertThat(Collections.min(numbers)).isEqualTo(1);
        assertThat(gaps(numbers)).isEmpty();
        assertThat(gaps(List.of(1, 2, 4))).containsExactly(3);
    }

    @Test
    void noTwoRevisionsShareAnIdentifier() {
        assertThat(shared(revisions())).isEmpty();
        assertThat(shared(List.of(1, 2, 2, 3))).containsExactly(2);
    }
}
