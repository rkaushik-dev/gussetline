package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 4, flight application: which flights the fare may be used on.
 *
 * <p>It names flight numbers, or bans codeshares, or insists on the filing carrier's own metal. The
 * last of those is the one that surprises people, because a codeshare sector marketed by the filing
 * carrier and operated by somebody else is the filing carrier's flight number and is not its flight,
 * and a fare filed own metal only refuses it. Only the sectors of the component being priced are
 * checked.
 *
 * @param flights the flight designators the fare may be used on, upper case; none filed means any
 * @param ownMetalOnly whether every sector has to be operated by the carrier named
 * @param noCodeshare whether a sector operated by another carrier than the one marketing it is
 *     refused
 * @param carrier the carrier whose metal own metal only means, normalised
 */
public record FlightApplication(
        List<String> flights, boolean ownMetalOnly, boolean noCodeshare, Optional<String> carrier)
        implements Provision {

    /**
     * Normalises the carrier and upper cases the flight list.
     *
     * @throws ValidationError when own metal only is filed with no carrier, or the carrier is
     *     malformed
     */
    public FlightApplication {
        Objects.requireNonNull(carrier, "carrier");
        carrier = carrier.map(Carriers::normaliseCode);
        flights = flights.stream().map(Text::code).toList();
        if (ownMetalOnly && carrier.isEmpty()) {
            throw new ValidationError("own metal only has to say whose metal");
        }
    }

    /** Nothing filed. */
    public FlightApplication() {
        this(List.of(), false, false, Optional.empty());
    }

    /** A list of flights the fare may be used on, and nothing else. */
    public FlightApplication(List<String> flights) {
        this(flights, false, false, Optional.empty());
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.FLIGHT_APPLICATION;
    }

    /** Whether nothing is filed. A carrier named alone is no condition. */
    public boolean isEmpty() {
        return flights.isEmpty() && !ownMetalOnly && !noCodeshare;
    }

    /**
     * Fails on the first sector of the component off the flight list (naming the {@code sector} and
     * what was {@code permitted}), or a codeshare when they are banned, or operated by somebody else
     * when own metal is filed (each naming the {@code sector} and who it is {@code operated_by}).
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        for (Segment segment : context.component().segments()) {
            if (!flights.isEmpty() && !flights.contains(segment.designator())) {
                return fails(
                        category(),
                        segment.designator() + " is not on the filed flight list",
                        Details.of("sector", segment.designator(), "permitted", flights));
            }
            if (noCodeshare && segment.isCodeshare()) {
                return fails(
                        category(),
                        segment.designator() + " is a codeshare and the fare refuses them",
                        Details.of("sector", segment.designator(), "operated_by", segment.operatedBy()));
            }
            if (ownMetalOnly && !segment.operatedBy().equals(carrier.orElseThrow())) {
                return fails(
                        category(),
                        segment.designator() + " is operated by " + segment.operatedBy() + " and the fare wants "
                                + carrier.orElseThrow() + " metal",
                        Details.of("sector", segment.designator(), "operated_by", segment.operatedBy()));
            }
        }
        return passes(category(), "every sector is on a permitted flight");
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private List<String> flights = List.of();
        private boolean ownMetalOnly;
        private boolean noCodeshare;
        private Optional<String> carrier = Optional.empty();

        private Builder() {}

        public Builder flights(List<String> value) {
            flights = List.copyOf(value);
            return this;
        }

        public Builder ownMetalOnly(boolean value) {
            ownMetalOnly = value;
            return this;
        }

        public Builder noCodeshare(boolean value) {
            noCodeshare = value;
            return this;
        }

        public Builder carrier(String value) {
            carrier = Optional.of(value);
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when own metal only is filed with no carrier
         */
        public FlightApplication build() {
            return new FlightApplication(flights, ownMetalOnly, noCodeshare, carrier);
        }
    }
}
