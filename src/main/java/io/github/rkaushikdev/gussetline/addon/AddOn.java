package io.github.rkaushikdev.gussetline.addon;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Market;
import java.util.Objects;

/**
 * One filed arbitrary: an amount between a gateway and an interior point.
 *
 * <p>An add-on attaches at one end of a gateway fare. Nuremberg to New York is the Frankfurt fare
 * plus the Nuremberg add-on at the origin end, and if the passenger also goes on to Hartford the
 * Hartford add-on attaches at the destination end. An add-on is an amount, not a fare: it has no
 * advance purchase, no minimum stay and no combinability of its own.
 *
 * <p>The carrier is normalised on the way in and both points fold to the cities the tariff files,
 * so an arbitrary given off JFK is held off NYC.
 *
 * @param carrier the carrier that filed it, upper case
 * @param gateway the city of the gateway fare it is added to
 * @param point the interior city it reaches
 * @param amount what it adds, never negative
 * @param end which end of the gateway fare it attaches to
 * @param bookingClass the booking class it is filed for, or empty
 * @param routingNumber the routing it is filed with, or empty
 * @param fareClass the fare class it is filed for, or empty
 */
public record AddOn(
        String carrier,
        String gateway,
        String point,
        Money amount,
        End end,
        String bookingClass,
        String routingNumber,
        String fareClass) {

    /**
     * Normalises the carrier and folds both points to cities.
     *
     * @throws ValidationError when the carrier is malformed, a point is unknown, both points are one
     *     city (naming the {@code point}), or the amount is negative (naming the {@code amount})
     */
    public AddOn {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(bookingClass, "bookingClass");
        Objects.requireNonNull(routingNumber, "routingNumber");
        Objects.requireNonNull(fareClass, "fareClass");

        carrier = Carriers.normaliseCode(carrier);
        gateway = Geography.cityOf(gateway);
        point = Geography.cityOf(point);
        if (gateway.equals(point)) {
            throw new ValidationError("an add-on reaches somewhere else", Details.of("point", point));
        }
        if (amount.isNegative()) {
            throw new ValidationError("an add-on is not negative", Details.of("amount", amount.toString()));
        }
    }

    /** An arbitrary at the origin end, filed for no particular booking class, routing or fare class. */
    public AddOn(String carrier, String gateway, String point, Money amount) {
        this(carrier, gateway, point, amount, End.ORIGIN);
    }

    /** An arbitrary at either end, filed for no particular booking class, routing or fare class. */
    public AddOn(String carrier, String gateway, String point, Money amount, End end) {
        this(carrier, gateway, point, amount, end, "", "", "");
    }

    /**
     * The pair this arbitrary covers, in the direction it is travelled: from the point to the
     * gateway at the origin end, and from the gateway to the point at the destination end.
     */
    public Market market() {
        if (end == End.ORIGIN) {
            return new Market(point, gateway);
        }
        return new Market(gateway, point);
    }

    /** The arbitrary as the fare calculation notes it: {@code LH HAM/FRA 40.00 EUR at the origin}. */
    @Override
    public String toString() {
        return carrier + " " + point + "/" + gateway + " " + amount + " at the " + end.value();
    }
}
