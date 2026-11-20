package io.github.rkaushikdev.gussetline.discounts;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Discounts by passenger type, and the two things they do not come off.
 *
 * <p>A child pays seventy five percent of the fare. That is one sentence and four places where it
 * goes wrong.
 *
 * <p>The percentage comes off the fare and off the Q surcharges, because those are fare. It does not
 * come off the mileage surcharge as a separate step, because the mileage surcharge is already a
 * percentage of a fare that has been discounted. It does not come off a flat tax at all, and it does
 * come off a percentage tax, because a percentage tax is a percentage of a discounted fare and
 * follows it down without anybody doing anything.
 *
 * <p>And the age is read on the date of departure and not on the date of booking. A child who turns
 * twelve between the two is a child on the ticket and an adult in the airport, and the fare that
 * priced them is still the child fare, which is a conversation the desk has about twice a year.
 */
public final class Discounts {

    /** The carriers whose usual discounts the desk ships with. */
    private static final List<String> SHIPPED_CARRIERS = List.of("LH", "BA", "AF", "KL", "UA", "AA", "DL");

    private Discounts() {}

    /**
     * Take the discount off a component total in neutral units, or leave it alone.
     *
     * @throws ValidationError when the carrier designator is malformed
     * @see #discountedNuc(BigDecimal, String, Passenger, LocalDate, DiscountStore, Trace)
     */
    public static BigDecimal discountedNuc(
            BigDecimal nuc, String carrier, Passenger passenger, LocalDate departure, DiscountStore store) {
        return discountedNuc(nuc, carrier, passenger, departure, store, new Trace());
    }

    /**
     * Take the discount off a component total in neutral units, or leave it alone, and say which on
     * the trace.
     *
     * <p>An adult is never discounted and leaves no step. A passenger the carrier files nothing for
     * pays the adult fare, which is noted and is not a refusal. A discount that applies is recorded
     * as a pass with its designator.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public static BigDecimal discountedNuc(
            BigDecimal nuc,
            String carrier,
            Passenger passenger,
            LocalDate departure,
            DiscountStore store,
            Trace trace) {
        if (passenger.kind() == PassengerType.ADULT) {
            return nuc;
        }
        Optional<Discount> found = store.find(carrier, passenger, departure);
        if (found.isEmpty()) {
            trace.note(
                    Stage.SELECTION, "no " + passenger.kind().value() + " discount filed, pricing at the adult fare");
            return nuc;
        }
        Discount discount = found.get();
        BigDecimal reduced = discount.applyTo(nuc);
        trace.passed(
                Stage.SELECTION,
                discount + " takes " + nuc + " to " + reduced,
                Details.of("designator", discount.name()));
        return reduced;
    }

    /**
     * The discounts the desk sees on nearly every carrier it sells: an infant on a lap, an infant in
     * a seat, a child and a youth, each with its age range.
     */
    public static DiscountStore defaultDiscountStore() {
        DiscountStore store = new DiscountStore();
        for (String carrier : SHIPPED_CARRIERS) {
            store.add(new Discount(carrier, PassengerType.INFANT, BigDecimal.valueOf(90), "IN90").withMaxAge(1));
            store.add(new Discount(carrier, PassengerType.INFANT_WITH_SEAT, BigDecimal.valueOf(25), "IN25")
                    .withMaxAge(1));
            store.add(new Discount(carrier, PassengerType.CHILD, BigDecimal.valueOf(25), "CH25")
                    .withMinAge(2)
                    .withMaxAge(11));
            store.add(new Discount(carrier, PassengerType.YOUTH, BigDecimal.valueOf(10), "YT10")
                    .withMinAge(12)
                    .withMaxAge(24));
        }
        return store;
    }
}
