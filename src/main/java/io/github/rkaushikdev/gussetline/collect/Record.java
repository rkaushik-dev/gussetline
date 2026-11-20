package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * One parsed line of a fixed width payload: its tag, and the value of every field that was there.
 *
 * <p>An optional field left blank on the line has no value at all, which {@link #contains} and
 * {@link #find} tell apart from a value that is present.
 *
 * @param tag the record tag
 * @param values each field's value by name, in the order of the layout
 * @param lineNumber the line it was read from, counting from one, or zero for a record built by hand
 */
public record Record(String tag, Map<String, Object> values, int lineNumber) {

    /** Keeps the order given. A field has a value or is absent, so no value is null. */
    public Record {
        values.forEach((name, value) -> Objects.requireNonNull(value, name));
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A record that was not read from a line. */
    public Record(String tag, Map<String, ?> values) {
        this(tag, new LinkedHashMap<>(values), 0);
    }

    /**
     * A field's value.
     *
     * @throws NoSuchElementException when the record has no value for the field
     */
    public Object get(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new NoSuchElementException(name);
        }
        return value;
    }

    /**
     * A field's value as the type its kind reads to: {@code String} for text, {@code Integer},
     * {@code BigDecimal} for an amount, {@code LocalDate} or {@link DayTime}.
     *
     * @throws NoSuchElementException when the record has no value for the field
     * @throws ClassCastException when the value is of another type
     */
    public <T> T get(String name, Class<T> type) {
        return type.cast(get(name));
    }

    /** A field's value, or the fallback when the record has none. */
    public Object getOrDefault(String name, Object fallback) {
        return values.getOrDefault(name, fallback);
    }

    /** A field's value, when the record has one. */
    public Optional<Object> find(String name) {
        return Optional.ofNullable(values.get(name));
    }

    /**
     * A field's value as a type, when the record has one.
     *
     * @throws ClassCastException when the value is of another type
     */
    public <T> Optional<T> find(String name, Class<T> type) {
        return find(name).map(type::cast);
    }

    /** Whether the record has a value for the field. */
    public boolean contains(String name) {
        return values.containsKey(name);
    }

    /** The tag, then every value in quoted form: {@code F1 {'basis': 'KLWAP7RT', 'amount': 420.00}}. */
    @Override
    public String toString() {
        return tag + " " + Text.repr(values);
    }
}
