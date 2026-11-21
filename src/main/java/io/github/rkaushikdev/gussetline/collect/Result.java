package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Objects;

/**
 * What one task did.
 *
 * <p>A task that was skipped is a result too, with no attempts. A task skipped because the quota was
 * spent is not the same as one that was never planned, and only the result can tell them apart
 * afterwards.
 *
 * @param task the task
 * @param outcome what happened, in the run report's terms
 * @param offers how many things the payload gave up, offers and quotes together
 * @param digest the archive key of the payload, or empty text when none arrived
 * @param fresh whether the payload was new to the archive
 * @param attempts how many calls it took; nought when none went out
 * @param problems how many parts of the payload could not be read
 * @param reason why it did not succeed, or empty text when it did
 */
public record Result(
        Task task,
        Outcome outcome,
        int offers,
        String digest,
        boolean fresh,
        int attempts,
        int problems,
        String reason) {

    public Result {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(reason, "reason");
    }

    /** A task that came to an outcome without a call or a payload. */
    public Result(Task task, Outcome outcome) {
        this(task, outcome, "");
    }

    /** A task that came to an outcome for a reason, without a call or a payload. */
    public Result(Task task, Outcome outcome, String reason) {
        this(task, outcome, 0, "", true, 0, 0, reason);
    }

    /** A result built a part at a time, starting from no offers, no payload, no calls and no reason. */
    public static Builder builder(Task task, Outcome outcome) {
        return new Builder(task, outcome);
    }

    /** Whether the task succeeded. */
    public boolean ok() {
        return outcome == Outcome.OK;
    }

    /**
     * The task and what came of it: {@code gds FRA-NYC 2025-06-04 1 offers}, or the outcome and the
     * reason, {@code gds FRA-NYC 2025-06-04 budget quota}.
     */
    @Override
    public String toString() {
        if (ok()) {
            return task + " " + offers + " offers";
        }
        return Text.strip(task + " " + outcome.value() + " " + reason);
    }

    /** The parts of a result named one at a time, each starting at its default. */
    public static final class Builder {

        private final Task task;
        private final Outcome outcome;
        private int offers;
        private String digest = "";
        private boolean fresh = true;
        private int attempts;
        private int problems;
        private String reason = "";

        private Builder(Task task, Outcome outcome) {
            this.task = task;
            this.outcome = outcome;
        }

        public Builder offers(int value) {
            offers = value;
            return this;
        }

        public Builder digest(String value) {
            digest = value;
            return this;
        }

        public Builder fresh(boolean value) {
            fresh = value;
            return this;
        }

        public Builder attempts(int value) {
            attempts = value;
            return this;
        }

        public Builder problems(int value) {
            problems = value;
            return this;
        }

        public Builder reason(String value) {
            reason = value;
            return this;
        }

        public Result build() {
            return new Result(task, outcome, offers, digest, fresh, attempts, problems, reason);
        }
    }
}
