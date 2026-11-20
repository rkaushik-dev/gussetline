package io.github.rkaushikdev.gussetline.retention;

import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the desk throws away, and the three things it never does.
 *
 * <p>A year of collecting is nine million observations and forty gigabytes of raw payloads, almost
 * none of which anybody will ever read again. Keeping all of it is a machine that fills up on a
 * Sunday. Throwing away the wrong part of it is worse, because the parts that matter are not the
 * big ones.
 *
 * <p>An observation is a reading of a market at a moment and it rolls off. Once the departure has
 * gone, nobody will ever ask what the fare was, and the row is several hundred bytes of nothing.
 *
 * <p>A recommendation never rolls off. It is what the desk told a customer, the savings report
 * reads it, and a report that changes when old rows are deleted is a report nobody can put in front
 * of a manager. There is no cutoff here that touches one.
 *
 * <p>A run rolls off, but later than the observations do, because the reliability report is read
 * over a quarter and the coverage report reads runs to work out what was polled.
 *
 * <p>And the archive is content addressed, which is the trap. The same payload seen on thirty
 * mornings is one blob and thirty manifest rows, so deleting the blob because one of those rows is
 * old takes the other twenty nine with it. Nothing here deletes a blob that any surviving row still
 * names.
 */
public final class Retention {

    /** How long an observation is kept when nobody says otherwise, in days. */
    public static final int OBSERVATION_DAYS = 400;

    /** How long a run is kept when nobody says otherwise, in days. */
    public static final int RUN_DAYS = 120;

    /** How long a raw payload is kept when nobody says otherwise, in days. */
    public static final int ARCHIVE_DAYS = 90;

    private Retention() {}

    /** The numbers the desk ships with. */
    public static Policy defaultPolicy() {
        return new Policy();
    }

    /**
     * Split a manifest into what survives and which blobs are still named.
     *
     * <p>A row survives when it was seen at or after {@code before}, or when it carries no
     * {@code seen_at} stamp at all, which is kept rather than guessed at. The digests come off the
     * surviving rows and not off the deleted ones, which is the whole point: a payload seen on thirty
     * mornings is one blob and thirty rows, and deleting it because one row is old takes the other
     * twenty nine with it.
     *
     * @param rows manifest rows, each with a {@code digest} and a {@code seen_at} stamp
     * @param before the cutoff; anything seen earlier goes
     * @throws java.time.format.DateTimeParseException when a stamp is not a time
     */
    public static <R extends Map<String, ?>> Kept<R> keepable(List<R> rows, LocalDateTime before) {
        List<R> kept = new ArrayList<>();
        for (R row : rows) {
            Object seen = row.get("seen_at");
            if (isMissing(seen) || !Times.parseIso(Text.plain(seen)).isBefore(before)) {
                kept.add(row);
            }
        }
        Set<String> named = new LinkedHashSet<>();
        for (R row : kept) {
            Object digest = row.get("digest");
            if (!isMissing(digest)) {
                named.add(Text.plain(digest));
            }
        }
        return new Kept<>(kept, named);
    }

    private static boolean isMissing(Object value) {
        return value == null || value instanceof CharSequence text && text.isEmpty();
    }
}
