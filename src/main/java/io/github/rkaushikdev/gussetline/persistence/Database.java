package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.config.Settings;
import java.util.Objects;

/**
 * The engine and the units of work opened on it, made once per process.
 *
 * <p>A database made from settings makes its engine and closes it when it is closed. One made over an
 * engine somebody else made, as a test suite's shared engine is, leaves the engine to them.
 */
public final class Database implements AutoCloseable {

    /** The URL of a database that lives in memory and goes when it is closed. */
    public static final String IN_MEMORY = "jdbc:h2:mem:";

    private final Engine engine;
    private final boolean ownsEngine;

    /** The database the settings name, quiet about the statements it runs. Nothing connects yet. */
    public Database(Settings settings) {
        this(settings, false);
    }

    /**
     * The database the settings name. Nothing connects until a unit is opened or the schema built.
     *
     * @param echo whether every statement is printed as it runs
     */
    public Database(Settings settings, boolean echo) {
        this(Engine.make(settings, echo), true);
    }

    /** A database over an engine somebody else made and closes. Closing the database leaves it open. */
    public Database(Engine engine) {
        this(engine, false);
    }

    private Database(Engine engine, boolean ownsEngine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ownsEngine = ownsEngine;
    }

    /**
     * A throwaway database in memory with the schema already built, private to whoever made it. Close
     * it and it is gone.
     */
    public static Database inMemory() {
        Database database = new Database(new Settings().withDatabase(IN_MEMORY));
        try {
            database.createSchema();
        } catch (RuntimeException trouble) {
            database.close();
            throw trouble;
        }
        return database;
    }

    /** The settings the engine was made from. */
    public Settings settings() {
        return engine.settings();
    }

    /** The engine underneath. */
    public Engine engine() {
        return engine;
    }

    /** Build the whole schema, or bring an older one up to date. For tests and a first run, not for deploys. */
    public void createSchema() {
        engine.createSchema();
    }

    /**
     * A transaction that rolls back unless somebody commits it, with every repository sharing it.
     * Open it in a try-with-resources statement: closing it rolls back whatever was not committed,
     * whether the block finished or threw.
     */
    public UnitOfWork unit() {
        return new UnitOfWork(engine.entityManagerFactory().createEntityManager());
    }

    /**
     * Let go of every connection, when this database made its engine; a database in memory goes with
     * them. An engine somebody else made is left to them. Disposing twice does nothing more.
     */
    public void dispose() {
        if (ownsEngine) {
            engine.close();
        }
    }

    /** The same as {@link #dispose()}. */
    @Override
    public void close() {
        dispose();
    }

    /** {@code database at jdbc:h2:file:./var/gussetline}, or {@code database in memory}. */
    @Override
    public String toString() {
        return settings().inMemory() ? "database in memory" : "database at " + engine.url();
    }
}
