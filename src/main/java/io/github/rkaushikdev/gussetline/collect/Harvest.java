package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.offers.Offer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one payload gave up, and everything it did not: one result shape for every supplier
 * reader.
 *
 * <p>The problems travel with the offers. A reader that kept its problem list to itself once cost a
 * month of malformed offers, dropped silently because the only code that read the problems was a
 * test.
 *
 * <p>A harvest carries both kinds of output. That is not a compromise: a calendar page really does
 * produce quotes and no offers, a gateway response really does produce offers and no quotes, and a
 * caller that wants offers asks for offers and gets an empty list from the calendar.
 *
 * <p>A harvest is filled as a payload is read, and is not safe to fill from several threads at once.
 */
public final class Harvest {

    private final List<Offer> offers;
    private final List<Quote> quotes;
    private final List<String> problems;
    private final String source;

    /** Nothing at all, from nowhere in particular. */
    public Harvest() {
        this("");
    }

    /** Nothing yet, from a source. */
    public Harvest(String source) {
        this(List.of(), List.of(), List.of(), source);
    }

    /** Offers, quotes and problems, from nowhere in particular. */
    public Harvest(List<Offer> offers, List<Quote> quotes, List<String> problems) {
        this(offers, quotes, problems, "");
    }

    /** Offers, quotes and problems from a source. The harvest keeps copies of the lists. */
    public Harvest(List<Offer> offers, List<Quote> quotes, List<String> problems, String source) {
        this.offers = new ArrayList<>(offers);
        this.quotes = new ArrayList<>(quotes);
        this.problems = new ArrayList<>(problems);
        this.source = Objects.requireNonNull(source, "source");
    }

    /** A harvest of offers alone. */
    public static Harvest offersOnly(List<Offer> offers) {
        return offersOnly(offers, "");
    }

    /** A harvest of offers alone, from a source. */
    public static Harvest offersOnly(List<Offer> offers, String source) {
        return new Harvest(offers, List.of(), List.of(), source);
    }

    /** A harvest of quotes alone. */
    public static Harvest quotesOnly(List<Quote> quotes) {
        return quotesOnly(quotes, "");
    }

    /** A harvest of quotes alone, from a source. */
    public static Harvest quotesOnly(List<Quote> quotes, String source) {
        return new Harvest(List.of(), quotes, List.of(), source);
    }

    /** A harvest of one problem and nothing else. */
    public static Harvest nothing(String problem) {
        return nothing(problem, "");
    }

    /** A harvest of one problem and nothing else, from a source. */
    public static Harvest nothing(String problem, String source) {
        return new Harvest(List.of(), List.of(), List.of(problem), source);
    }

    /** Fold a run's worth of harvests into one, keeping every problem. The result has no source. */
    public static Harvest combine(List<Harvest> harvests) {
        Harvest joined = new Harvest();
        harvests.forEach(joined::extend);
        return joined;
    }

    /** The offers, in the order they were read. */
    public List<Offer> offers() {
        return List.copyOf(offers);
    }

    /** The quotes, in the order they were read. */
    public List<Quote> quotes() {
        return List.copyOf(quotes);
    }

    /** What could not be read, one sentence each, in the order it was found. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** The name of the source the payload came from, or empty text when nobody said. */
    public String source() {
        return source;
    }

    public void addOffer(Offer offer) {
        offers.add(Objects.requireNonNull(offer, "offer"));
    }

    public void addQuote(Quote quote) {
        quotes.add(Objects.requireNonNull(quote, "quote"));
    }

    public void addProblem(String problem) {
        problems.add(Objects.requireNonNull(problem, "problem"));
    }

    /** Whether everything in the payload could be read. */
    public boolean clean() {
        return problems.isEmpty();
    }

    /** Whether nothing came out, of either kind. Problems do not count. */
    public boolean isEmpty() {
        return offers.isEmpty() && quotes.isEmpty();
    }

    /** How many things came out, of whichever kind. */
    public int size() {
        return offers.size() + quotes.size();
    }

    /** The offer with the lowest total, the first one read on a tie, or none when there are no offers. */
    public Optional<Offer> cheapestOffer() {
        return offers.stream().min(Comparator.comparing(offer -> offer.total().amount()));
    }

    /**
     * The quote with the lowest amount, the first one read on a tie, leaving out the sold out days.
     * None when every day is sold out or there are no quotes.
     */
    public Optional<Quote> cheapestQuote() {
        return quotes.stream().filter(quote -> !quote.soldOut()).min(Comparator.comparing(quote -> quote.amount()
                .amount()));
    }

    /**
     * The lowest price of either kind, as text, for the schedule: the cheapest offer's total when
     * there are offers, else the cheapest live quote's amount, else empty text.
     *
     * <p>Text because the schedule only compares it for equality, and two amounts in different
     * currencies would compare as numbers without complaining.
     */
    public String cheapestAmount() {
        Optional<Offer> offer = cheapestOffer();
        if (offer.isPresent()) {
            return offer.get().total().toString();
        }
        return cheapestQuote().map(quote -> quote.amount().toString()).orElse("");
    }

    /** The first quote for a departure date, or none. */
    public Optional<Quote> quoteOn(LocalDate day) {
        return quotes.stream().filter(quote -> quote.departure().equals(day)).findFirst();
    }

    /** Take everything another harvest holds, its problems included. Its source is not taken. */
    public void extend(Harvest other) {
        offers.addAll(other.offers);
        quotes.addAll(other.quotes);
        problems.addAll(other.problems);
    }

    /** {@code 1 offers, 2 quotes, 1 problems}, or {@code nothing} when nothing came out. */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (!offers.isEmpty()) {
            parts.add(offers.size() + " offers");
        }
        if (!quotes.isEmpty()) {
            parts.add(quotes.size() + " quotes");
        }
        if (parts.isEmpty()) {
            parts.add("nothing");
        }
        if (!problems.isEmpty()) {
            parts.add(problems.size() + " problems");
        }
        return String.join(", ", parts);
    }
}
