package io.github.rkaushikdev.gussetline.retention;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What survives a sweep of the archive manifest, and the blobs those survivors still name.
 *
 * @param rows the manifest rows that stay, in their order
 * @param named the digest of every blob a surviving row names, in the order first named; a blob
 *     outside this set is one nothing reads again
 * @param <R> the shape of one manifest row
 */
public record Kept<R>(List<R> rows, Set<String> named) {

    public Kept {
        rows = List.copyOf(rows);
        named = Collections.unmodifiableSet(new LinkedHashSet<>(named));
    }
}
