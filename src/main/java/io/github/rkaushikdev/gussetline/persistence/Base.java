package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The conventions every table follows, and the naming rule that makes migrations dull.
 *
 * <p>Every constraint is named by a rule rather than by the database. Without that, one database
 * invents a name, another invents a different one, and a migration that drops a constraint works on
 * the machine it was written on and fails on the one it is deployed to. With it, the name is a
 * function of the table and the columns and every database agrees:
 *
 * <ul>
 *   <li>a primary key is {@code pk_<table>};
 *   <li>a unique constraint is {@code uq_<table>_<columns>};
 *   <li>an index on one column is {@code ix_<table>_<column>}, and an index on several carries a
 *       name of its own, such as {@code ix_fares_market};
 *   <li>a foreign key is {@code fk_<table>_<columns>_<referred table>};
 *   <li>a check constraint is {@code ck_<table>_<name>}.
 * </ul>
 *
 * <p>Columns are joined with underscores. Each name is written out in the mapping's annotations and
 * in the migrations, and the tests hold both to the methods here.
 *
 * <p>Amounts are stored as text and not as numerics ({@link Amount}). That looks wrong and it is
 * deliberate. Some databases have no decimal type and hand back a float, which loses the third
 * decimal on a Kuwaiti dinar and the hundredth on everything else. Text round trips exactly through
 * every database the desk has been asked to run on, and the money type is doing the arithmetic
 * anyway. An amount and its currency never travel apart: a table that holds money holds two
 * columns, {@code <prefix>_amount} and {@code <prefix>_currency}, and not one, because a single
 * string holding both cannot be summed or ordered by the database, and the reports do both.
 *
 * <p>Times are stored without a zone, because everything here is UTC ({@link Stamp}).
 */
public final class Base {

    private Base() {}

    /** The name of a table's primary key: {@code pk_airports}. */
    public static String primaryKey(String table) {
        return "pk_" + table;
    }

    /** The name of a unique constraint over these columns: {@code uq_rates_table_name_currency_effective}. */
    public static String unique(String table, List<String> columns) {
        return "uq_" + table + "_" + String.join("_", columns);
    }

    /** The name of an index over these columns: {@code ix_airports_city}. */
    public static String index(String table, List<String> columns) {
        return "ix_" + table + "_" + String.join("_", columns);
    }

    /** The name of a foreign key from these columns: {@code fk_coupons_ticket_id_tickets}. */
    public static String foreignKey(String table, List<String> columns, String referred) {
        return "fk_" + table + "_" + String.join("_", columns) + "_" + referred;
    }

    /** The name of a check constraint: {@code ck_fares_positive}. */
    public static String check(String table, String name) {
        return "ck_" + table + "_" + name;
    }

    /**
     * Money rebuilt from its two columns, or nothing when either half is missing.
     *
     * @param amount the amount column, which may be null
     * @param currency the currency column, which may be null or empty
     */
    public static Optional<Money> toMoney(BigDecimal amount, String currency) {
        if (amount == null || currency == null || currency.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Money(amount, currency));
    }
}
