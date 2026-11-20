package io.github.rkaushikdev.gussetline.retention;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * How long each kind of row is worth keeping, in days.
 *
 * <p>Recommendations are not here and there is no setting for them. A savings report that changes
 * when old rows are deleted cannot be put in front of anybody, so the only safe number is no number
 * at all.
 *
 * @param observationDays how long a reading of a market is kept after its departure
 * @param runDays how long a collection run is kept
 * @param archiveDays how long a raw payload is kept; never longer than the runs
 */
public record Policy(int observationDays, int runDays, int archiveDays) {

    /**
     * Refuses a policy that keeps anything for less than a day, or keeps payloads longer than the
     * runs that explain them.
     *
     * @throws ValidationError naming the setting when a retention is under a day, and with both
     *     numbers when the payloads would outlive the runs
     */
    public Policy {
        atLeastADay("observation_days", observationDays);
        atLeastADay("run_days", runDays);
        atLeastADay("archive_days", archiveDays);
        if (runDays < archiveDays) {
            // The reliability report reads runs and the archive is what a replay reads. Keeping
            // payloads for a run that has been deleted is disk with nothing to say about it.
            throw new ValidationError(
                    "runs are kept at least as long as the payloads they explain",
                    Details.of("runs", runDays, "archive", archiveDays));
        }
    }

    /** The numbers the desk ships with: {@link Retention#OBSERVATION_DAYS} and the others. */
    public Policy() {
        this(Retention.OBSERVATION_DAYS, Retention.RUN_DAYS, Retention.ARCHIVE_DAYS);
    }

    /** Observations for a departure before this day go. */
    public LocalDate observationsBefore(LocalDate today) {
        return today.minusDays(observationDays);
    }

    /** Runs started before this moment, midnight at the start of the cutoff day, go. */
    public LocalDateTime runsBefore(LocalDate today) {
        return today.minusDays(runDays).atStartOfDay();
    }

    /**
     * Archive sightings from before this moment, midnight at the start of the cutoff day, go, and
     * with them any payload no later sighting names.
     */
    public LocalDateTime archiveBefore(LocalDate today) {
        return today.minusDays(archiveDays).atStartOfDay();
    }

    /** The policy in one line: {@code observations 400d, runs 120d, payloads 90d}. */
    @Override
    public String toString() {
        return "observations " + observationDays + "d, runs " + runDays + "d, payloads " + archiveDays + "d";
    }

    private static void atLeastADay(String setting, int days) {
        if (days < 1) {
            throw new ValidationError("a retention is at least a day", Details.of("setting", setting, "value", days));
        }
    }
}
