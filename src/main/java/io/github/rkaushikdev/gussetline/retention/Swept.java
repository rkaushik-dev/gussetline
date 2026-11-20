package io.github.rkaushikdev.gussetline.retention;

/**
 * What one sweep removed, per kind, or would remove on a dry run.
 *
 * @param observations readings of a market removed
 * @param runs collection runs removed
 * @param manifestRows archive sightings removed
 * @param blobs raw payloads removed
 * @param blobsKept payloads kept because a surviving sighting still names them, which is the number
 *     worth reading: a payload seen on thirty mornings is one blob and thirty rows
 */
public record Swept(int observations, int runs, int manifestRows, int blobs, int blobsKept) {

    /** A sweep that removed nothing. */
    public Swept() {
        this(0, 0, 0, 0, 0);
    }

    /** Whether the sweep removed anything at all. A kept payload is not a removal. */
    public boolean anything() {
        return observations != 0 || runs != 0 || manifestRows != 0 || blobs != 0;
    }

    /**
     * The sweep in one line: {@code 1 observations, 2 runs, 3 payloads, 1 payloads still
     * referenced}.
     */
    @Override
    public String toString() {
        return observations + " observations, " + runs + " runs, " + blobs + " payloads, " + blobsKept
                + " payloads still referenced";
    }
}
