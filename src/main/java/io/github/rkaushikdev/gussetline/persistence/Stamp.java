package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.util.Times;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A timestamp as the schema stores it: without a zone, because everything here is UTC.
 *
 * <p>A stamp column holds a {@link LocalDateTime} read as UTC, to the microsecond. A time that
 * carries an offset is converted before it is written rather than refused, so a caller that has a
 * zone on its clock does not have to know that this layer does not.
 */
public final class Stamp {

    private Stamp() {}

    /** The same instant as a UTC wall clock: {@code 2025-06-02T12:00+02:00} is {@code 2025-06-02T10:00}. */
    public static LocalDateTime utc(OffsetDateTime moment) {
        return moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Now, in UTC, to the microsecond a stamp column keeps. */
    public static LocalDateTime now() {
        return Times.utcNow();
    }
}
