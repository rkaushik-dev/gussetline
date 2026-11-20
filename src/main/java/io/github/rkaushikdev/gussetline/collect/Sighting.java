package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One time a payload was seen, whether or not it was new.
 *
 * <p>A payload seen again is not stored again, but the sighting still goes in the manifest, marked
 * not fresh. That line is what says the market was checked and had not moved, which is invisible if
 * only what changed is kept.
 *
 * @param digest the key the payload is stored under ({@link Archive#digestOf})
 * @param supplier the name of the supplier that sent it
 * @param url where it came from
 * @param seenAt when it arrived
 * @param status the HTTP status it came with
 * @param fresh whether this was the first time the archive held it
 * @param size how many bytes it was, before compression
 * @param context what it was an answer to, such as the market and departure asked about
 */
public record Sighting(
        String digest,
        String supplier,
        String url,
        LocalDateTime seenAt,
        int status,
        boolean fresh,
        int size,
        Map<String, Object> context) {

    /** The status a sighting carries when nobody says otherwise. */
    public static final int OK = 200;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    public Sighting {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(seenAt, "seenAt");
        context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** A fresh sighting with a plain success status, no size recorded and no context. */
    public Sighting(String digest, String supplier, String url, LocalDateTime seenAt) {
        this(digest, supplier, url, seenAt, OK, true, 0, Map.of());
    }

    /**
     * The sighting as a manifest row: {@code digest}, {@code supplier}, {@code url}, {@code seen_at}
     * in ISO 8601, {@code status}, {@code fresh}, {@code size} and a copy of the {@code context}.
     * The map belongs to the caller.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("digest", digest);
        row.put("supplier", supplier);
        row.put("url", url);
        row.put("seen_at", Times.iso(seenAt));
        row.put("status", status);
        row.put("fresh", fresh);
        row.put("size", size);
        row.put("context", new LinkedHashMap<>(context));
        return row;
    }

    /**
     * The sighting in one line, to the minute, with the start of the digest: {@code 2025-05-12 09:00
     * gds new abcdef01}, or {@code same} for a payload already held.
     */
    @Override
    public String toString() {
        return MINUTE.format(seenAt) + " " + supplier + " " + (fresh ? "new" : "same") + " " + Text.slice(digest, 0, 8);
    }
}
