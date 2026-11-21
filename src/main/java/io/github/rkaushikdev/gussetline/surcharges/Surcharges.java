package io.github.rkaushikdev.gussetline.surcharges;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Q surcharges, which are part of the fare and are not a tax.
 *
 * <p>A Q surcharge is filed against a market and added to the fare component in neutral units before
 * any minimum check. That ordering is the whole reason this is a separate step. Add it after the
 * minimum checks and a component that should have been raised to the Chicago fare quietly is not,
 * because the surcharge already carried it past the comparison.
 *
 * <p>There are two shapes ({@link Scope}). A component surcharge is charged once for the whole
 * component however many sectors it has. A sector surcharge is charged for each sector that matches,
 * so a component with two matching sectors pays twice. The filings look nearly identical and getting
 * the shape wrong is a doubling error that only shows on connecting itineraries.
 */
public final class Surcharges {

    private Surcharges() {}

    /** {@link #assessComponent(FareComponent, SurchargeTable, Converter, LocalDate, Trace)} with nothing recorded. */
    public static List<AppliedSurcharge> assessComponent(
            FareComponent component, SurchargeTable table, Converter converter, LocalDate ticketing) {
        return assessComponent(component, table, converter, ticketing, new Trace());
    }

    /**
     * Every surcharge this component owes, counted the right number of times, in the order the
     * carrier filed them.
     *
     * <p>Only the priced fare's carrier's filings are read, and only those whose travel window holds
     * the departure of the first flown sector. A component filing attaches when it covers the
     * component's two ends; a sector filing is charged once for every element it covers and not at
     * all when it covers none. One charge is the filed amount in neutral units, truncated, and the
     * count multiplies it. Each charge is recorded as a passed {@link Stage#SURCHARGE} step with its
     * {@code times}.
     *
     * @throws ValidationError when the component has not been priced, or a filing's currency has no
     *     rate on the date
     */
    public static List<AppliedSurcharge> assessComponent(
            FareComponent component, SurchargeTable table, Converter converter, LocalDate ticketing, Trace trace) {
        Fare fare = component
                .fare()
                .orElseThrow(() -> new ValidationError("a surcharge check runs after the component is priced"));

        LocalDate departure = component.segments().get(0).departure().toLocalDate();
        List<AppliedSurcharge> found = new ArrayList<>();

        for (QSurcharge surcharge : table.forCarrier(fare.carrier())) {
            if (!surcharge.flyableOn(departure)) {
                continue;
            }
            int times;
            if (surcharge.scope() == Scope.COMPONENT) {
                if (!surcharge.covers(component.origin(), component.destination())) {
                    continue;
                }
                times = 1;
            } else {
                times = 0;
                for (Element element : component.elements()) {
                    if (surcharge.covers(element.origin(), element.destination())) {
                        times++;
                    }
                }
                if (times == 0) {
                    continue;
                }
            }
            BigDecimal per = converter.toNuc(surcharge.amount(), ticketing);
            AppliedSurcharge applied =
                    new AppliedSurcharge(surcharge, times, per.multiply(BigDecimal.valueOf(times), Numbers.CONTEXT));
            found.add(applied);
            trace.passed(Stage.SURCHARGE, applied.toString(), Details.of("times", times));
        }

        return List.copyOf(found);
    }

    /** What the charges add up to, in neutral units. Nothing adds up to zero. */
    public static BigDecimal totalNuc(Iterable<AppliedSurcharge> applied) {
        List<BigDecimal> amounts = new ArrayList<>();
        applied.forEach(item -> amounts.add(item.nuc()));
        return Numbers.sum(amounts);
    }

    /** A copy of the component with each charge put on in turn, each under its own note. */
    public static FareComponent applyAll(FareComponent component, Iterable<AppliedSurcharge> applied) {
        FareComponent result = component;
        for (AppliedSurcharge item : applied) {
            result = result.withSurcharge(item.nuc(), item.reason());
        }
        return result;
    }
}
