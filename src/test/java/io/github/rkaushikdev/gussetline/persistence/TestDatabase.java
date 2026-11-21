package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.config.Settings;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * The suite's database: one H2 in memory, migrated once for the whole run and emptied before each
 * test that asks for it.
 *
 * <p>Building the entity manager factory for every test costs more than the tests do, so this keeps
 * one database and one factory and gives every test a database of its own in effect: the migrated
 * schema with nothing in it, and the generated keys counting from one again.
 *
 * <p>A test opens a {@link TestSession} in {@code @BeforeEach} with {@link #session()} and closes
 * it in {@code @AfterEach}; anything it needs beyond that it reaches through {@link #engine()}.
 */
public final class TestDatabase {

    /** Named, and kept open for the whole run, so every connection in the pool sees one database. */
    public static final String URL = "jdbc:h2:mem:gussetline-tests;DB_CLOSE_DELAY=-1";

    private static Engine shared;

    private TestDatabase() {}

    /** The shared engine, its schema migrated and its factory checked against it. */
    public static synchronized Engine engine() {
        if (shared == null) {
            Engine made = Engine.make(new Settings().withDatabase(URL));
            made.createSchema();
            made.entityManagerFactory();
            shared = made;
        }
        return shared;
    }

    /** A session with its transaction begun, over the shared database emptied of every row. */
    public static TestSession session() {
        Engine engine = engine();
        empty(engine);
        return new TestSession(engine.entityManagerFactory().createEntityManager());
    }

    /**
     * Asserts that the database refused the work for breaking one of its constraints: a duplicate
     * key, a missing parent, a value left out. The refusal can come at either step, since a row whose
     * key the database generates is written as soon as it is added.
     */
    public static void assertRefused(ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOf(PersistenceException.class)
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    private static void empty(Engine engine) {
        try (Connection connection = engine.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : engine.tableNames()) {
                if (!table.equals(Engine.HISTORY_TABLE)) {
                    statement.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
                }
            }
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not empty the test database", trouble);
        }
    }
}
