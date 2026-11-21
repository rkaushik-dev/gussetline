package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Ndc.amountOf;
import static io.github.rkaushikdev.gussetline.collect.Ndc.asList;
import static io.github.rkaushikdev.gussetline.collect.Ndc.moneyOf;
import static io.github.rkaushikdev.gussetline.collect.Ndc.parse;
import static io.github.rkaushikdev.gussetline.collect.Ndc.pick;
import static io.github.rkaushikdev.gussetline.collect.Ndc.snake;
import static io.github.rkaushikdev.gussetline.collect.Ndc.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectNdcTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 5, 15, 9, 0);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A map of the pairs given, in order, nulls allowed. */
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> made = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            made.put((String) pairs[index], pairs[index + 1]);
        }
        return made;
    }

    /** The map with some of its pairs replaced. */
    private static Map<String, Object> over(Map<String, Object> base, Object... pairs) {
        Map<String, Object> made = new LinkedHashMap<>(base);
        made.putAll(object(pairs));
        return made;
    }

    private static Map<String, Object> aSegment(Object... changes) {
        return over(
                object(
                        "marketingCarrier", "LH",
                        "flightNumber", "400",
                        "origin", "FRA",
                        "destination", "JFK",
                        "departureTime", "2025-06-04T10:25:00+02:00",
                        "arrivalTime", "2025-06-04T13:05:00-04:00",
                        "bookingClass", "K",
                        "cabin", "Y"),
                changes);
    }

    private static Map<String, Object> anOffer(Object... changes) {
        return over(
                object(
                        "offerId",
                        "abc",
                        "priceDetail",
                        object(
                                "baseAmount", object("amount", "420.00", "currencyCode", "EUR"),
                                "taxes",
                                        List.of(
                                                object("code", "DE", "amount", "70.83", "currencyCode", "EUR"),
                                                object("code", "YQ", "amount", "180.00", "currencyCode", "EUR")),
                                "totalAmount", object("amount", "670.83", "currencyCode", "EUR")),
                        "paxJourneys",
                        List.of(object("segments", List.of(aSegment()))),
                        "fareBasis",
                        List.of("KLWAP7RT"),
                        "seatsRemaining",
                        4,
                        "baggageAllowance",
                        object("pieces", 1),
                        "refundable",
                        false,
                        "validatingCarrier",
                        "LH"),
                changes);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    /** The offers in an envelope, or one ordinary offer when none is given. */
    private static String document(Object... offers) {
        List<Object> listed = offers.length == 0 ? List.of(anOffer()) : List.of(offers);
        return json(object("offerResponse", object("offers", listed)));
    }

    @Test
    void snakeCaseConversion() {
        assertThat(snake("marketingCarrier")).isEqualTo("marketing_carrier");
        assertThat(snake("code")).isEqualTo("code");
        assertThat(snake("HTTPStatus")).isEqualTo("h_t_t_p_status");
    }

    @Test
    void aKeyIsFoundUnderEitherSpelling() {
        assertThat(pick(Map.of("marketingCarrier", "LH"), "marketingCarrier")).contains("LH");
        assertThat(pick(Map.of("marketing_carrier", "LH"), "marketingCarrier")).contains("LH");
        assertThat(pick(Map.of("carrier", "LH"), "marketingCarrier", "carrier")).contains("LH");
    }

    @Test
    void aNullIsAbsentAndTakesTheDefault() {
        Map<String, Object> nullCabin = new HashMap<>();
        nullCabin.put("cabin", null);
        assertThat(pick(nullCabin, "cabin").orElse("Y")).isEqualTo("Y");
        assertThat(pick(Map.of(), "cabin").orElse("Y")).isEqualTo("Y");
        assertThat(pick("not a dict", "cabin").orElse("Y")).isEqualTo("Y");
    }

    @Test
    void oneOfSomethingReadsAsAListOfOne() {
        assertThat(asList(Map.of("a", 1))).containsExactly(Map.of("a", 1));
        assertThat(asList(List.of(Map.of("a", 1), Map.of("b", 2)))).containsExactly(Map.of("a", 1), Map.of("b", 2));
        assertThat(asList(null)).isEmpty();
    }

    @Test
    void anAmountIsReadFromItsTextAndNeverThroughAFloat() {
        assertThat(amountOf("420.00")).isEqualByComparingTo("420.00");
        assertThat(amountOf(420)).isEqualByComparingTo(BigDecimal.valueOf(420));
        assertThat(amountOf(new BigDecimal("420.00"))).isEqualByComparingTo("420.00");
    }

    @Test
    void aJsonNumberSurvivesTheRoundTripExactly() throws JsonProcessingException {
        // 0.1 in binary floating point is not a tenth, and reading it through its shortest text keeps
        // the text that round trips rather than the binary expansion.
        assertThat(amountOf(JSON.readValue("420.10", Object.class))).isEqualByComparingTo("420.1");
    }

    @Test
    void aBooleanOrANullIsNotAnAmount() {
        assertThatThrownBy(() -> amountOf(true)).isInstanceOf(MalformedPayload.class);
        assertThatThrownBy(() -> amountOf(null)).isInstanceOf(MalformedPayload.class);
        assertThatThrownBy(() -> amountOf("four hundred")).isInstanceOf(MalformedPayload.class);
    }

    @Test
    void moneyNeedsBothAnAmountAndACode() {
        assertThat(moneyOf(Map.of("amount", "420.00", "currencyCode", "EUR"))).contains(Money.of("420.00", "EUR"));
        assertThat(moneyOf(Map.of("amount", "420.00"))).isEmpty();
        assertThat(moneyOf(Map.of("currencyCode", "EUR"))).isEmpty();
        assertThat(moneyOf(null)).isEmpty();
        assertThat(moneyOf("nonsense")).isEmpty();
    }

    @Test
    void moneyTakesAFallbackCurrencyWhenTheObjectHasNone() {
        assertThat(moneyOf(Map.of("amount", "70.83"), "EUR")).contains(Money.of("70.83", "EUR"));
    }

    @Test
    void aTimestampDropsItsOffsetBecauseSectorsAreWallClock() {
        assertThat(when("2025-06-04T10:25:00+02:00")).contains(LocalDateTime.of(2025, 6, 4, 10, 25));
        assertThat(when("2025-06-04T10:25:00Z")).contains(LocalDateTime.of(2025, 6, 4, 10, 25));
        assertThat(when("2025-06-04T10:25:00")).contains(LocalDateTime.of(2025, 6, 4, 10, 25));
    }

    @Test
    void aTimestampWeCannotReadIsAbsentRatherThanWrong() {
        assertThat(when("the fourth of June")).isEmpty();
        assertThat(when("")).isEmpty();
        assertThat(when(null)).isEmpty();
    }

    @Test
    void aPayloadThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> parse("<html>gateway error</html>", SEEN)).isInstanceOf(MalformedPayload.class);
    }

    @Test
    void aPayloadWithNoOffersIsRefused() {
        assertThatThrownBy(() -> parse(json(object("offerResponse", object("offers", List.of()))), SEEN))
                .isInstanceOf(MalformedPayload.class);
    }

    @Test
    void aSimpleOfferComesAcrossWhole() {
        Harvest reading = parse(document(), SEEN);
        assertThat(reading.clean()).isTrue();
        assertThat(reading.size()).isEqualTo(1);
        Offer offer = reading.offers().get(0);
        assertThat(offer.source()).isEqualTo("ndc");
        assertThat(offer.breakdown().base()).isEqualTo(Money.of("420.00", "EUR"));
        assertThat(offer.fareBases()).containsExactly("KLWAP7RT");
        assertThat(offer.seats()).contains(4);
        assertThat(offer.baggage().pieces()).contains(1);
        assertThat(offer.refundable()).contains(false);
    }

    @Test
    void theTaxesSplitIntoGovernmentAndCarrierMoney() {
        Offer offer = parse(document(), SEEN).offers().get(0);
        assertThat(offer.breakdown().taxTotal()).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(offer.breakdown().surchargeTotal()).isEqualTo(Money.of("180.00", "EUR"));
    }

    @Test
    void theSegmentTimesAreWallClockAtEachEnd() {
        Segment segment =
                parse(document(), SEEN).offers().get(0).itinerary().segments().get(0);
        assertThat(segment.departure()).isEqualTo(LocalDateTime.of(2025, 6, 4, 10, 25));
        assertThat(segment.arrival()).isEqualTo(LocalDateTime.of(2025, 6, 4, 13, 5));
        assertThat(segment.dayChange()).isZero();
    }

    @Test
    void aJourneySentAsOneObjectRatherThanAListStillReads() {
        Map<String, Object> offer = anOffer("paxJourneys", object("segments", aSegment()));
        Harvest reading = parse(document(offer), SEEN);
        assertThat(reading.size()).isEqualTo(1);
        assertThat(reading.offers().get(0).itinerary().segments()).hasSize(1);
    }

    @Test
    void theSnakeCaseSpellingOfTheWholeDocumentReadsToo() {
        Map<String, Object> sector = object(
                "marketing_carrier", "LH",
                "flight_number", "400",
                "origin", "FRA",
                "destination", "JFK",
                "departure_time", "2025-06-04T10:25:00",
                "arrival_time", "2025-06-04T13:05:00",
                "booking_class", "K");
        Map<String, Object> tree = object(
                "offer_response",
                object(
                        "offers",
                        List.of(object(
                                "price_detail",
                                object("base_amount", object("amount", "420.00", "currency_code", "EUR")),
                                "pax_journeys",
                                List.of(object("segments", List.of(sector))),
                                "fare_basis",
                                List.of("KLWAP7RT")))));
        Harvest reading = parse(json(tree), SEEN);
        assertThat(reading.size()).isEqualTo(1);
    }

    @Test
    void anOfferWithNoBaseAmountIsAProblemAndTheOthersSurvive() {
        Map<String, Object> broken = anOffer("priceDetail", object("taxes", List.of()));
        Harvest reading = parse(document(broken, anOffer()), SEEN);
        assertThat(reading.size()).isEqualTo(1);
        assertThat(reading.problems().get(0)).contains("no base amount");
    }

    @Test
    void aSegmentMissingAFieldIsAProblemAndNamesTheOffer() {
        Map<String, Object> broken =
                anOffer("paxJourneys", List.of(object("segments", List.of(aSegment("bookingClass", null)))));
        Harvest reading = parse(document(broken), SEEN);
        assertThat(reading.size()).isZero();
        assertThat(reading.problems()).containsExactly("offer 0: a segment was missing a field");
    }

    @Test
    void segmentsThatDoNotJoinUpAreAProblem() {
        Map<String, Object> broken = anOffer(
                "paxJourneys",
                List.of(object("segments", List.of(aSegment(), aSegment("origin", "BOS", "destination", "FRA")))));
        Harvest reading = parse(document(broken), SEEN);
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0)).contains("do not join up");
    }

    @Test
    void anOfferWithNoSegmentsAtAllIsAProblem() {
        Harvest reading = parse(document(anOffer("paxJourneys", List.of())), SEEN);
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0)).contains("could not be assembled");
    }

    @Test
    void aTaxMissingItsCodeIsDroppedRatherThanFailingTheOffer() {
        Map<String, Object> offer = anOffer(
                "priceDetail",
                object(
                        "baseAmount", object("amount", "420.00", "currencyCode", "EUR"),
                        "taxes", List.of(object("amount", "70.83", "currencyCode", "EUR"))));
        Harvest reading = parse(document(offer), SEEN);
        assertThat(reading.size()).isEqualTo(1);
        assertThat(reading.offers().get(0).breakdown().taxes()).isEmpty();
    }

    @Test
    void aReadingReadsBackWithBothCounts() {
        Harvest reading = parse(document(anOffer("priceDetail", object()), anOffer()), SEEN);
        assertThat(reading).hasToString("1 offers, 1 problems");
    }

    @Test
    void aBagAllowanceSentAsABareNumberStillReads() {
        Map<String, Object> offer = anOffer("baggageAllowance", 2);
        assertThat(parse(document(offer), SEEN).offers().get(0).baggage().pieces())
                .contains(2);
    }

    @Test
    void aDocumentWithNoEnvelopeIsReadAsTheEnvelope() {
        Harvest reading = parse(json(object("offers", List.of(anOffer()))), SEEN);
        assertThat(reading.size()).isEqualTo(1);
    }

    @Test
    void aWesternOffsetIsCutAsWellAsAnEasternOne() {
        // A minus offset has the same shape as the hyphens in the date, so a careless cut takes the
        // text apart at the year instead.
        assertThat(when("2025-06-04T13:05:00-04:00")).contains(LocalDateTime.of(2025, 6, 4, 13, 5));
        assertThat(when("2025-06-04T13:05:00+05:30")).contains(LocalDateTime.of(2025, 6, 4, 13, 5));
    }

    @Test
    void anArrivalOnTheNextDayGivesADayChange() {
        Map<String, Object> sector = aSegment(
                "origin", "FRA",
                "destination", "NRT",
                "departureTime", "2025-06-04T17:20:00+02:00",
                "arrivalTime", "2025-06-05T11:55:00+09:00");
        Map<String, Object> offer = anOffer("paxJourneys", List.of(object("segments", List.of(sector))));
        Segment segment = parse(document(offer), SEEN)
                .offers()
                .get(0)
                .itinerary()
                .segments()
                .get(0);
        assertThat(segment.dayChange()).isEqualTo(1);
    }

    @Test
    void anArrivalBeforeTheDepartureDoesNotGiveANegativeDayChange() {
        Map<String, Object> sector =
                aSegment("departureTime", "2025-06-04T17:00:00+02:00", "arrivalTime", "2025-06-04T10:30:00-04:00");
        Map<String, Object> offer = anOffer("paxJourneys", List.of(object("segments", List.of(sector))));
        Segment segment = parse(document(offer), SEEN)
                .offers()
                .get(0)
                .itinerary()
                .segments()
                .get(0);
        assertThat(segment.dayChange()).isZero();
    }
}
