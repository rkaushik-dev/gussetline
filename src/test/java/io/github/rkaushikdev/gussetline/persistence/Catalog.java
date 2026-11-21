package io.github.rkaushikdev.gussetline.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

/**
 * What a database says its schema is, read from its information schema, lower case throughout.
 *
 * <p>Each reading is a set of lines, one per column, constraint or index, so two schemas compare
 * with {@code equals} and a difference prints as the lines that differ. Indexes the database makes
 * for itself to back a constraint are left out, since only the declared ones carry a name anybody
 * chose.
 */
public final class Catalog {

    private static final String TABLES = "select table_name from information_schema.tables"
            + " where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'";

    private static final String COLUMNS = "select column_name, is_nullable from information_schema.columns"
            + " where table_schema = 'PUBLIC' and table_name = ?";

    private static final String TYPES = "select column_name, data_type, character_maximum_length,"
            + " numeric_precision, numeric_scale, datetime_precision, is_identity"
            + " from information_schema.columns where table_schema = 'PUBLIC' and table_name = ?";

    private static final String PRIMARY_KEY = "select k.column_name, k.ordinal_position"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'PRIMARY KEY' and c.table_name = ?";

    private static final String CONSTRAINTS = "select c.constraint_type, c.constraint_name,"
            + " listagg(k.column_name, ',') within group (order by k.ordinal_position)"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.table_name = ?"
            + " group by c.constraint_type, c.constraint_name";

    private static final String UNIQUES = "select c.constraint_name,"
            + " listagg(k.column_name, ',') within group (order by k.ordinal_position)"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'UNIQUE' and c.table_name = ?"
            + " group by c.constraint_name";

    private static final String FOREIGN_KEYS = "select r.constraint_name,"
            + " listagg(k.column_name, ',') within group (order by k.ordinal_position),"
            + " min(u.table_name),"
            + " listagg(u.column_name, ',') within group (order by k.ordinal_position)"
            + " from information_schema.referential_constraints r"
            + " join information_schema.key_column_usage k on r.constraint_name = k.constraint_name"
            + " join information_schema.key_column_usage u on r.unique_constraint_name = u.constraint_name"
            + " and k.position_in_unique_constraint = u.ordinal_position"
            + " where k.table_schema = 'PUBLIC' and k.table_name = ?"
            + " group by r.constraint_name";

    private static final String INDEXES = "select i.index_name, i.index_type_name,"
            + " listagg(c.column_name, ',') within group (order by c.ordinal_position)"
            + " from information_schema.indexes i join information_schema.index_columns c"
            + " on i.index_name = c.index_name and i.table_name = c.table_name"
            + " where i.table_schema = 'PUBLIC' and i.table_name = ? and not i.is_generated"
            + " group by i.index_name, i.index_type_name";

    private final DataSource source;

    public Catalog(DataSource source) {
        this.source = source;
    }

    /** The tables. */
    public Set<String> tables() {
        return new TreeSet<>(rows(TABLES, null));
    }

    /** Each column and whether it may be empty: {@code city | no}. */
    public Set<String> columns(String table) {
        return new TreeSet<>(rows(COLUMNS, table));
    }

    /** Each column's type, its length, precision and scale, and whether it is generated. */
    public Set<String> types(String table) {
        return new TreeSet<>(rows(TYPES, table));
    }

    /** The primary key's columns and their places in it: {@code id | 1}. */
    public Set<String> primaryKey(String table) {
        return new TreeSet<>(rows(PRIMARY_KEY, table));
    }

    /** Every constraint, by type, name and columns: {@code unique | uq_rates_table_name_currency_effective | ...}. */
    public Set<String> constraints(String table) {
        return new TreeSet<>(rows(CONSTRAINTS, table));
    }

    /** Each unique constraint, by name and columns. */
    public Set<String> uniques(String table) {
        return new TreeSet<>(rows(UNIQUES, table));
    }

    /** Each foreign key, by name, its columns and what they refer to. */
    public Set<String> foreignKeys(String table) {
        return new TreeSet<>(rows(FOREIGN_KEYS, table));
    }

    /** Each declared index, by name, kind and columns. */
    public Set<String> indexes(String table) {
        return new TreeSet<>(rows(INDEXES, table));
    }

    private List<String> rows(String sql, String table) {
        List<String> found = new ArrayList<>();
        try (Connection connection = source.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (table != null) {
                statement.setString(1, table.toUpperCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                int width = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1; column <= width; column++) {
                        row.append(column == 1 ? "" : " | ").append(rows.getString(column));
                    }
                    found.add(row.toString().toLowerCase(Locale.ROOT));
                }
            }
        } catch (SQLException trouble) {
            throw new IllegalStateException(sql, trouble);
        }
        return found;
    }
}
