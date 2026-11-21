package io.github.rkaushikdev.gussetline.load;

import java.util.Objects;

/**
 * What reading and applying a filing file in one call hands back: the file as it was read, and what
 * applying it did to the store.
 *
 * @param <L> what the feed reads a file into
 * @param <A> what the feed counts when it applies one
 * @param load the file as read, problems and all
 * @param applied what it did to the store
 */
public record Loaded<L, A>(L load, A applied) {

    public Loaded {
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(applied, "applied");
    }
}
