package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.collect.Problem;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A category 10 file, read but not yet applied.
 *
 * @param records every record the file files, in file order
 * @param deleted every carrier and fare basis the file withdraws, in file order
 * @param problems every line that did not read, the layout's refusals first and then the records
 *     refused by themselves, each in file order
 */
public record CombinabilityLoad(List<Combinability> records, List<Withdrawn> deleted, List<Problem> problems) {

    public CombinabilityLoad {
        records = List.copyOf(records);
        deleted = List.copyOf(deleted);
        problems = List.copyOf(problems);
    }

    /** Whether every line read. */
    public boolean clean() {
        return problems.isEmpty();
    }

    /**
     * The record the file files for a carrier and a fare basis, each given in any case, or empty when
     * it files none.
     */
    public Optional<Combinability> get(String carrier, String fareBasis) {
        String wantedCarrier = carrier.toUpperCase(Locale.ROOT);
        String wantedBasis = fareBasis.toUpperCase(Locale.ROOT);
        return records.stream()
                .filter(record -> record.carrier().equals(wantedCarrier)
                        && record.fareBasis().equals(wantedBasis))
                .findFirst();
    }

    /** How many records the file files. */
    public int size() {
        return records.size();
    }

    /** The counts: {@code 1 records, 1 deleted, 0 problems}. */
    @Override
    public String toString() {
        return records.size() + " records, " + deleted.size() + " deleted, " + problems.size() + " problems";
    }

    /**
     * A carrier and fare basis whose combinability the carrier has withdrawn.
     *
     * @param carrier the carrier, upper case
     * @param fareBasis the fare basis, upper case
     */
    public record Withdrawn(String carrier, String fareBasis) {

        public Withdrawn {
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(fareBasis, "fareBasis");
        }
    }
}
