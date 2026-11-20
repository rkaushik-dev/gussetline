package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import java.util.List;
import java.util.Optional;

/**
 * Everything a fixed width payload gave up, and everything it could not.
 *
 * @param records the records read, in payload order
 * @param problems the lines that did not read, in payload order
 */
public record Reading(List<Record> records, List<Problem> problems) {

    public Reading {
        records = List.copyOf(records);
        problems = List.copyOf(problems);
    }

    /** Whether every line read. */
    public boolean clean() {
        return problems.isEmpty();
    }

    /** The records with a tag, in payload order. */
    public List<Record> tagged(String tag) {
        return records.stream().filter(record -> record.tag().equals(tag)).toList();
    }

    /** The first record with a tag, when there is one. */
    public Optional<Record> first(String tag) {
        return records.stream().filter(record -> record.tag().equals(tag)).findFirst();
    }

    /**
     * The first record with a tag, which the payload cannot make sense without.
     *
     * @throws MalformedPayload when there is none, naming the tag
     */
    public Record require(String tag) {
        return first(tag)
                .orElseThrow(() -> new MalformedPayload("the payload has no such record", Details.of("tag", tag)));
    }

    /** How many records were read. */
    public int size() {
        return records.size();
    }

    /** {@code 2 records, 0 problems}. */
    @Override
    public String toString() {
        return records.size() + " records, " + problems.size() + " problems";
    }
}
