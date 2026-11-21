package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.collect.Problem;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A whole fare filing file, read but not yet applied.
 *
 * @param carrier the carrier the header says the file is for, upper case, or empty with no header
 * @param filedOn the day the header says the file was filed, when there is a header
 * @param sequence the header's sequence number, or zero with no header
 * @param filings every record that read into a filing, in file order
 * @param problems every line that did not, the layout's refusals first and then the records the
 *     tariff refused, each in file order
 * @param declared how many records the trailer says the file carries, when there is a trailer
 */
public record FareLoad(
        String carrier,
        Optional<LocalDate> filedOn,
        int sequence,
        List<Filing> filings,
        List<Problem> problems,
        Optional<Integer> declared) {

    public FareLoad {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(filedOn, "filedOn");
        Objects.requireNonNull(declared, "declared");
        filings = List.copyOf(filings);
        problems = List.copyOf(problems);
    }

    /** Whether every line read. */
    public boolean clean() {
        return problems.isEmpty();
    }

    /**
     * Whether the trailer's count agrees with what was read. A file with no trailer has nothing to
     * disagree with.
     *
     * <p>The bad records count too: the carrier counted every record it wrote, including the ones
     * that could not be used, so a count without them would fail on every real file.
     */
    public boolean counted() {
        return declared.map(count -> count == filings.size() + problems.size()).orElse(true);
    }

    /** The filings with one action, in file order. */
    public List<Filing> of(Filing.Action action) {
        return filings.stream().filter(filing -> filing.action() == action).toList();
    }

    /** How many filings were read. */
    public int size() {
        return filings.size();
    }

    /** The carrier, the day and the counts: {@code LH 2025-03-01: 1 filings, 0 problems}. */
    @Override
    public String toString() {
        String when = filedOn.map(LocalDate::toString).orElse("no date");
        return carrier + " " + when + ": " + filings.size() + " filings, " + problems.size() + " problems";
    }
}
