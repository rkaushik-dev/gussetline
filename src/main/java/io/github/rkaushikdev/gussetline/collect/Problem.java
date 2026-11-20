package io.github.rkaushikdev.gussetline.collect;

/**
 * Something wrong with one line of a payload, kept rather than thrown so the rest still reads.
 *
 * @param lineNumber the line, counting from one
 * @param line the line as it arrived, or the record it was read into
 * @param reason what was wrong with it
 */
public record Problem(int lineNumber, String line, String reason) {

    /** {@code line 4: unknown record tag 'Z9'}. */
    @Override
    public String toString() {
        return "line " + lineNumber + ": " + reason;
    }
}
