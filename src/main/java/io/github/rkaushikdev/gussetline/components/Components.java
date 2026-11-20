package io.github.rkaushikdev.gussetline.components;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * Fare components and pricing units, which are not the same thing.
 *
 * <p>A fare component ({@link FareComponent}) is the run of sectors one published fare covers, from
 * one fare break point to the next. A pricing unit ({@link PricingUnit}) is the group of components
 * that get priced together and share a minimum check, and it is the pricing unit that decides
 * whether a round trip minimum applies, not the component.
 *
 * <p>Frankfurt to New York and back is two components in one round trip pricing unit. Frankfurt to
 * New York, New York to Los Angeles and Los Angeles home is three components in one circle trip
 * pricing unit. Frankfurt to New York on one ticket and New York to Boston on another is two
 * components in two one way pricing units, and nothing about the second one can make the first
 * cheaper.
 *
 * <p>Getting the grouping wrong does not produce a wrong looking answer. It produces a plausible
 * answer that is out by whatever the minimum check would have added.
 */
public final class Components {

    private Components() {}

    /**
     * Cut a run of elements into components at the given indices.
     *
     * <p>{@code breaks} holds the index of the last element of each component except the final one,
     * so a two component split of four elements at {@code [1]} gives two and two. The indices have to
     * climb and stay inside the run, because an out of order break silently produces an empty
     * component and a component that covers the whole journey twice.
     *
     * @throws ValidationError when there is nothing to split, a break does not climb past the one
     *     before it, or a break is at or past the last element (naming the break {@code at})
     */
    public static List<FareComponent> splitAt(List<? extends Element> elements, List<Integer> breaks) {
        if (elements.isEmpty()) {
            throw new ValidationError("nothing to split");
        }
        int last = -1;
        for (int point : breaks) {
            if (point <= last) {
                throw new ValidationError("fare break points have to climb", Details.of("at", point));
            }
            if (point >= elements.size() - 1) {
                throw new ValidationError("a fare break past the end", Details.of("at", point));
            }
            last = point;
        }

        List<FareComponent> parts = new ArrayList<>();
        int start = 0;
        for (int point : breaks) {
            parts.add(new FareComponent(elements.subList(start, point + 1)));
            start = point + 1;
        }
        parts.add(new FareComponent(elements.subList(start, elements.size())));
        return List.copyOf(parts);
    }
}
