package io.github.rkaushikdev.gussetline.mileage;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MileageUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.distance.Mileage;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mileage principle, which is the oldest surviving idea in the tariff.
 *
 * <p>A fare between two cities buys a maximum permitted mileage, not a route. Fly further than the
 * maximum and the fare goes up in five percent steps, and there are only five steps. Come in at a
 * hundred and twenty six percent of the maximum and there is no fare at all, because the ladder stops
 * at twenty five.
 *
 * <p>Two allowances soften it. Extra mileage allowance is filed per city pair and comes off the
 * flown total before the comparison. The ticketed point mileage deduction takes off the mileage of
 * certain intermediate points, which is how a carrier makes its own hub free. Both are subtractions
 * from the flown figure and never additions to the maximum, and that matters because they are
 * applied in a fixed order and the result differs if you swap them.
 */
public final class MileagePrinciple {

    /**
     * The surcharge ladder: the percentage of the maximum each step reaches up to, inclusive, and the
     * surcharge it carries. Nothing past twenty five is sellable on a mileage fare.
     */
    public static final List<Band> SURCHARGE_BANDS = List.of(
            new Band(100, 0),
            new Band(105, 5),
            new Band(110, 10),
            new Band(115, 15),
            new Band(120, 20),
            new Band(125, 25));

    /** The highest surcharge on the ladder, in percent. */
    public static final int MAX_SURCHARGE = 25;

    /** The maximum permitted mileage is this much of the shortest ticketed point mileage. */
    private static final BigDecimal MAXIMUM_FACTOR = new BigDecimal("1.2");

    private MileagePrinciple() {}

    /**
     * The surcharge step a percentage falls into, or nothing if it is off the end of the ladder.
     */
    public static Optional<Integer> bandFor(int percentage) {
        for (Band band : SURCHARGE_BANDS) {
            if (percentage <= band.limit()) {
                return Optional.of(band.surcharge());
            }
        }
        return Optional.empty();
    }

    /**
     * {@link #check(String, String, List, MileageTable, Optional, Optional, Optional)} with no
     * indicator, no allowances and nothing recorded.
     */
    public static MileageResult check(String origin, String destination, List<String> via, MileageTable table) {
        return check(origin, destination, via, table, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Run the mileage principle over one fare component.
     *
     * <p>The flown figure is the sum of the ticketed point mileages of each sector and not the great
     * circle from end to end, because a routing through a hub is longer and that is the whole point of
     * the check. A component off the ladder is still answered, with {@link #MAX_SURCHARGE} and not
     * permitted.
     *
     * <p>When there is a trace, a great circle standing in anywhere is noted, and the verdict is
     * recorded against {@link Stage#MILEAGE} with the chargeable figure as {@code flown} and the
     * {@code maximum}.
     *
     * @param origin where the component starts
     * @param destination where it ends
     * @param via the intermediate points, in order
     * @param table the published mileages
     * @param indicator the global indicator the mileages are read for, if any
     * @param allowances the filed deductions, if any
     * @param trace where to record the verdict, if anywhere
     * @throws MileageUnavailable naming the {@code origin} and the {@code destination} when either is
     *     blank, or when a point has nothing to measure from
     * @throws ValidationError when a point is neither a city nor an airport
     */
    public static MileageResult check(
            String origin,
            String destination,
            List<String> via,
            MileageTable table,
            Optional<GlobalIndicator> indicator,
            Optional<ExtraMileageAllowance> allowances,
            Optional<Trace> trace) {
        if (Text.isBlank(origin) || Text.isBlank(destination)) {
            throw new MileageUnavailable(
                    "a mileage check needs both ends", Details.of("origin", origin, "destination", destination));
        }
        List<String> points = new ArrayList<>(via.size() + 2);
        points.add(origin);
        points.addAll(via);
        points.add(destination);

        int flown = 0;
        boolean fallback = false;
        for (int index = 0; index < points.size() - 1; index++) {
            Mileage reading = table.tpm(points.get(index), points.get(index + 1), indicator);
            flown += reading.miles();
            fallback = fallback || reading.isFallback();
        }

        Mileage maximumReading = table.tpm(origin, destination, indicator);
        int maximum = maximumOf(maximumReading);
        fallback = fallback || maximumReading.isFallback();

        int deduction = allowances
                .map(filed -> filed.deductionFor(origin, destination, via))
                .orElse(0);

        MileageResult provisional = new MileageResult(flown, maximum, deduction, 0, true, fallback);
        Optional<Integer> surcharge = bandFor(provisional.percentage());
        MileageResult result = new MileageResult(
                flown, maximum, deduction, surcharge.orElse(MAX_SURCHARGE), surcharge.isPresent(), fallback);

        if (trace.isPresent()) {
            record(trace.get(), result);
        }
        return result;
    }

    /** {@link #maximumPermitted(String, String, MileageTable, Optional)} with no indicator. */
    public static int maximumPermitted(String origin, String destination, MileageTable table) {
        return maximumPermitted(origin, destination, table, Optional.empty());
    }

    /**
     * The maximum permitted mileage for a city pair.
     *
     * <p>Published as twenty percent above the shortest ticketed point mileage, with any fraction of
     * a mile dropped, which is the convention the tariff has used since it was printed on paper and
     * is why the number is never round.
     */
    public static int maximumPermitted(
            String origin, String destination, MileageTable table, Optional<GlobalIndicator> indicator) {
        return maximumOf(table.tpm(origin, destination, indicator));
    }

    private static int maximumOf(Mileage shortest) {
        return BigDecimal.valueOf(shortest.miles())
                .multiply(MAXIMUM_FACTOR)
                .setScale(0, RoundingMode.DOWN)
                .intValueExact();
    }

    private static void record(Trace trace, MileageResult result) {
        if (result.fallbackUsed()) {
            trace.note(Stage.MILEAGE, "a great circle stood in for a published mileage");
        }
        Map<String, Object> figures = Details.of("flown", result.chargeable(), "maximum", result.maximum());
        if (result.permitted()) {
            trace.passed(Stage.MILEAGE, result.toString(), figures);
        } else {
            trace.failedStep(Stage.MILEAGE, "over the mileage ladder, no fare constructs", figures);
        }
    }

    /**
     * One step of the surcharge ladder.
     *
     * @param limit the highest percentage of the maximum the step covers, inclusive
     * @param surcharge the surcharge it carries, in percent
     */
    public record Band(int limit, int surcharge) {}
}
