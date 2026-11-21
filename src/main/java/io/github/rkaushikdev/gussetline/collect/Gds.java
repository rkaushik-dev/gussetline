package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Field.Kind;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.flights.YearRoll;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.taxes.Taxes;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The pricing gateway, which is fixed width and stateful and old.
 *
 * <p>The response is a run of records that only means anything in order. A header ({@link #HEADER})
 * opens the payload and gives the year, an offer record ({@link #OFFER}) opens each priced option,
 * and the segments, fare components and taxes that follow belong to whichever offer record came
 * last. An end record ({@link #END}) closes the offer with the total the gateway is claiming.
 *
 * <p>The year is the part that bites. Segment records carry the day and month and not the year,
 * because in 1978 nobody was shopping across a new year, and a reader that assumes the header's year
 * gets every December booking for January travel wrong by twelve months. So the year is carried
 * forward and rolled when the month goes backwards ({@link YearRoll}), afresh for each offer.
 */
public final class Gds {

    /** The name the harvests and offers from this reader carry. */
    public static final String SOURCE = "gds";

    /** The header: which system sent it, the day it was stamped, the market and the currency. */
    public static final RecordSpec HEADER = new RecordSpec(
            "H1",
            new Field("source", 2, 8),
            new Field("stamp", 10, 8, Kind.DATE),
            new Field("origin", 18, 3),
            new Field("destination", 21, 3),
            new Field("currency", 24, 3));

    /** Opens one priced option: its reference, and the seats and bags when the gateway says. */
    public static final RecordSpec OFFER = new RecordSpec(
            "O1",
            new Field("reference", 2, 6),
            new Field("seats", 8, 2, Kind.INT, true),
            new Field("bags", 10, 2, Kind.INT, true));

    /** One flown sector of the option, its times as a month, day, hour and minute with no year. */
    public static final RecordSpec SECTOR = new RecordSpec(
            "S1",
            new Field("carrier", 2, 2),
            new Field("flight", 4, 4),
            new Field("origin", 8, 3),
            new Field("destination", 11, 3),
            new Field("departure", 14, 8, Kind.DAYTIME),
            new Field("arrival", 22, 8, Kind.DAYTIME),
            new Field("day_change", 30, 1, Kind.INT),
            new Field("booking_class", 31, 1),
            new Field("cabin", 32, 1),
            new Field("operating", 33, 2, Kind.TEXT, true));

    /** One fare component: its fare basis and its amount, which add into the base fare. */
    public static final RecordSpec COMPONENT = new RecordSpec(
            "F1",
            new Field("basis", 2, 10),
            new Field("currency", 12, 3),
            new Field("amount", 15, 9, Kind.AMOUNT),
            new Field("indicator", 24, 2));

    /** One tax or carrier surcharge on the option. */
    public static final RecordSpec TAX = new RecordSpec(
            "T1", new Field("code", 2, 3), new Field("currency", 5, 3), new Field("amount", 8, 9, Kind.AMOUNT));

    /** Closes the option with the total the gateway claims for it. */
    public static final RecordSpec END =
            new RecordSpec("E1", new Field("currency", 2, 3), new Field("amount", 5, 9, Kind.AMOUNT));

    /** Every record the gateway sends. */
    public static final Layout LAYOUT = new Layout(List.of(HEADER, OFFER, SECTOR, COMPONENT, TAX, END));

    /** What the header's source says on a payload from this gateway. */
    private static final String GATEWAY = "GDSFARE";

    private static final int AMOUNT_WIDTH = 9;

    private Gds() {}

    /** Read a whole gateway response sent as UTF-8 bytes. */
    public static Harvest parse(byte[] payload, LocalDateTime observedAt) {
        return read(LAYOUT.read(payload), observedAt);
    }

    /**
     * Read a whole gateway response into offers.
     *
     * <p>An offer that cannot be assembled, with no sectors, no fare component or sectors that do not
     * join up, is dropped and the rest are kept, because a gateway that sends one broken option out
     * of twelve is a normal Tuesday and refusing the payload would lose the other eleven. A line
     * that does not read is a problem on the harvest. Records before the first offer record belong
     * to no offer and are passed over.
     *
     * @throws MalformedPayload when there is no header, or the header names another system as the
     *     {@code source}
     * @throws ValidationError when a sector, a tax or an amount cannot describe a real one
     * @throws IllegalArgumentException when a sector's day or time does not exist
     */
    public static Harvest parse(String payload, LocalDateTime observedAt) {
        return read(LAYOUT.read(payload), observedAt);
    }

    /** The gateway's own nine digit implied decimal, for building requests: the amount in cents. */
    public static String amountField(BigDecimal value) {
        String cents = value.multiply(BigDecimal.valueOf(100), Numbers.CONTEXT)
                .toBigInteger()
                .toString();
        return cents.length() >= AMOUNT_WIDTH ? cents : "0".repeat(AMOUNT_WIDTH - cents.length()) + cents;
    }

    private static Harvest read(Reading reading, LocalDateTime observedAt) {
        Harvest harvest = new Harvest(
                List.of(),
                List.of(),
                reading.problems().stream().map(Problem::toString).toList(),
                SOURCE);
        Record header = reading.require(HEADER.tag());
        String source = header.get("source", String.class);
        if (!source.equals(GATEWAY)) {
            throw new MalformedPayload("not a pricing gateway payload", Details.of("source", source));
        }

        int year = header.get("stamp", LocalDate.class).getYear();
        Building current = null;
        YearRoll roll = new YearRoll(year);

        for (Record record : reading.records()) {
            String tag = record.tag();
            if (tag.equals(HEADER.tag())) {
                continue;
            }
            if (tag.equals(OFFER.tag())) {
                if (current != null) {
                    current.build(observedAt).ifPresent(harvest::addOffer);
                }
                roll = new YearRoll(year);
                current = new Building(record.find("seats", Integer.class), record.find("bags", Integer.class));
                continue;
            }
            if (current == null) {
                continue;
            }
            if (tag.equals(SECTOR.tag())) {
                current.sectors.add(sector(record, roll));
            } else if (tag.equals(COMPONENT.tag())) {
                current.addComponent(record);
            } else if (tag.equals(TAX.tag())) {
                current.taxes.add(tax(record));
            } else if (tag.equals(END.tag())) {
                current.total = money(record);
            }
        }

        if (current != null) {
            current.build(observedAt).ifPresent(harvest::addOffer);
        }
        return harvest;
    }

    /**
     * A sector, its year carried by the roll. The arrival is on the departure date at the arrival's
     * own time; the day change says how many days later it really is.
     */
    private static Segment sector(Record record, YearRoll roll) {
        DayTime leaves = record.get("departure", DayTime.class);
        LocalDate day = roll.resolve(leaves.month(), leaves.day());
        DayTime lands = record.get("arrival", DayTime.class);
        String flight = stripLeadingZeros(record.get("flight", String.class));
        return new Segment(
                record.get("carrier", String.class),
                flight.isEmpty() ? "0" : flight,
                record.get("origin", String.class),
                record.get("destination", String.class),
                at(day, leaves),
                at(day, lands),
                record.get("booking_class", String.class),
                cabinOf((String) record.getOrDefault("cabin", Cabin.ECONOMY.value())),
                record.find("operating", String.class).filter(code -> !code.isEmpty()),
                Optional.empty(),
                record.get("day_change", Integer.class),
                0,
                Optional.empty(),
                false);
    }

    private static LocalDateTime at(LocalDate day, DayTime clock) {
        try {
            return day.atTime(clock.hour(), clock.minute());
        } catch (DateTimeException noSuchTime) {
            throw new IllegalArgumentException(noSuchTime.getMessage(), noSuchTime);
        }
    }

    /**
     * The cabin a letter names, or economy for a letter the tariff does not price. Every supplier
     * reader falls back the same way.
     */
    static Cabin cabinOf(String letter) {
        return Arrays.stream(Cabin.values())
                .filter(cabin -> cabin.value().equals(letter))
                .findFirst()
                .orElse(Cabin.ECONOMY);
    }

    private static AppliedTax tax(Record record) {
        String code = record.get("code", String.class);
        return new AppliedTax(code, money(record), "", Taxes.CARRIER_IMPOSED.contains(code));
    }

    private static Money money(Record record) {
        return new Money(record.get("amount", BigDecimal.class), record.get("currency", String.class));
    }

    private static String stripLeadingZeros(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '0') {
            start++;
        }
        return text.substring(start);
    }

    /** One offer under construction, as its records arrive. */
    private static final class Building {

        private final Optional<Integer> seats;
        private final Optional<Integer> bags;
        private final List<Segment> sectors = new ArrayList<>();
        private final List<String> bases = new ArrayList<>();
        private final List<AppliedTax> taxes = new ArrayList<>();
        private Money base;
        private Money total;

        Building(Optional<Integer> seats, Optional<Integer> bags) {
            this.seats = seats;
            this.bags = bags;
        }

        /** A fare component's basis, and its amount added into the base. */
        void addComponent(Record record) {
            bases.add(record.get("basis", String.class));
            Money amount = money(record);
            base = base == null ? amount : base.plus(amount);
        }

        /** The offer, or none when the records did not add up to one. */
        Optional<Offer> build(LocalDateTime observedAt) {
            if (sectors.isEmpty() || base == null) {
                return Optional.empty();
            }
            Itinerary itinerary;
            try {
                itinerary = new Itinerary(sectors);
            } catch (ValidationError disjoint) {
                return Optional.empty();
            }
            Segment first = itinerary.segments().get(0);
            return Optional.of(Offer.builder()
                    .source(SOURCE)
                    .itinerary(itinerary)
                    .breakdown(new PriceBreakdown(base, taxes, Optional.ofNullable(total)))
                    .fareBases(bases)
                    .observedAt(observedAt)
                    .cabin(first.cabin())
                    .seats(seats)
                    .baggage(bags.map(Baggage::ofPieces).orElseGet(Baggage::new))
                    .validatingCarrier(first.marketingCarrier())
                    .build());
        }
    }
}
