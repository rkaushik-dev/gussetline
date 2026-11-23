package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which law a disrupted journey is compensated under: Regulation 261 itself, or the copy the United
 * Kingdom kept in its own law with sterling amounts.
 *
 * <p>The two read the same rules over the same distance bands and differ in three places: the
 * states they cover, the currency and the amounts, and which carriers bring an arrival under them.
 * The European regulation covers an arrival into the Union only on a carrier licensed in it, and
 * since the United Kingdom left that no longer includes a British one. The United Kingdom's copy
 * covers an arrival on a British carrier or on one licensed in the Union. So a Lufthansa flight from
 * New York into Heathrow is compensated in sterling, and a British Airways one from New York into
 * Frankfurt is not compensated at all.
 *
 * <p>A departure decides first: a journey that leaves a covered state is under that state's law,
 * whoever flies it and wherever it lands, and the passenger is never paid twice.
 */
public enum Regime {
    EU("EU 261", "EUR", 250, 400, 600, EuStates.ALL, EuStates.ALL),
    UK("UK 261", "GBP", 220, 350, 520, Set.of("GB"), EuStates.withUk());

    private final String label;
    private final String currency;
    private final Money shortHaul;
    private final Money mediumHaul;
    private final Money longHaul;
    private final Set<String> covered;
    private final Set<String> licensing;

    Regime(
            String label,
            String currency,
            int shortHaul,
            int mediumHaul,
            int longHaul,
            Set<String> covered,
            Set<String> licensing) {
        this.label = label;
        this.currency = currency;
        this.shortHaul = Money.of(shortHaul, currency);
        this.mediumHaul = Money.of(mediumHaul, currency);
        this.longHaul = Money.of(longHaul, currency);
        this.covered = covered;
        this.licensing = licensing;
    }

    /** The name the desk writes the law under: {@code UK 261}. */
    public String label() {
        return label;
    }

    /** The currency the law sets its amounts in. */
    public String currency() {
        return currency;
    }

    /** Whether a country, by code, is one this law covers departures from. */
    public boolean covers(String country) {
        return covered.contains(country);
    }

    /**
     * The law that governs a journey, or empty when neither does: the one whose state the journey
     * leaves, then the one whose state it lands in when the operating carrier of its first sector is
     * licensed where that law accepts.
     */
    public static Optional<Regime> governing(Itinerary journey) {
        String from = Geography.airportOrCityCountry(journey.origin());
        for (Regime regime : values()) {
            if (regime.covered.contains(from)) {
                return Optional.of(regime);
            }
        }
        String to = Geography.airportOrCityCountry(journey.destination());
        String operator = journey.segments().get(0).operatedBy();
        if (!Carriers.isKnown(operator)) {
            return Optional.empty();
        }
        String licence = Carriers.carrier(operator).country();
        for (Regime regime : values()) {
            if (regime.covered.contains(to) && regime.licensing.contains(licence)) {
                return Optional.of(regime);
            }
        }
        return Optional.empty();
    }

    /** The flat amount for a journey of this many kilometres. */
    Money amountFor(int kilometres) {
        if (kilometres <= Eu261.SHORT_KM) {
            return shortHaul;
        }
        return kilometres <= Eu261.MEDIUM_KM ? mediumHaul : longHaul;
    }

    /** The states of the Union with Norway and Switzerland, which read the regulation as members do. */
    private static final class EuStates {

        static final Set<String> ALL = Set.of(
                "AT", "BE", "CZ", "DE", "DK", "ES", "FI", "FR", "HU", "IE", "IT", "NL", "PL", "PT", "SE", "NO", "CH");

        static Set<String> withUk() {
            Set<String> both = new HashSet<>(ALL);
            both.add("GB");
            return Set.copyOf(both);
        }

        private EuStates() {}
    }
}
