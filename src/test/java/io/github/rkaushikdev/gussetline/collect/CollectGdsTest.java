package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Gds.COMPONENT;
import static io.github.rkaushikdev.gussetline.collect.Gds.END;
import static io.github.rkaushikdev.gussetline.collect.Gds.HEADER;
import static io.github.rkaushikdev.gussetline.collect.Gds.OFFER;
import static io.github.rkaushikdev.gussetline.collect.Gds.SECTOR;
import static io.github.rkaushikdev.gussetline.collect.Gds.TAX;
import static io.github.rkaushikdev.gussetline.collect.Gds.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.flights.YearRoll;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectGdsTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 2, 14, 9, 0);

    private static String header() {
        return HEADER.write(Map.of(
                "source", "GDSFARE",
                "stamp", LocalDate.of(2025, 2, 14),
                "origin", "FRA",
                "destination", "JFK",
                "currency", "EUR"));
    }

    private static String offerLine() {
        return offerLine("000001");
    }

    private static String offerLine(String reference) {
        return OFFER.write(Map.of("reference", reference, "seats", 4, "bags", 1));
    }

    private static String sector() {
        return sector(Map.of());
    }

    /** A sector record: LH 400 FRA to JFK on 4 March, 10:25 to 13:05, in K, with any field replaced. */
    private static String sector(Map<String, Object> over) {
        Map<String, Object> values = new HashMap<>();
        values.put("carrier", "LH");
        values.put("flight", "0400");
        values.put("origin", "FRA");
        values.put("destination", "JFK");
        values.put("departure", new DayTime(3, 4, 10, 25));
        values.put("arrival", new DayTime(3, 4, 13, 5));
        values.put("day_change", 0);
        values.put("booking_class", "K");
        values.put("cabin", "Y");
        values.putAll(over);
        return SECTOR.write(values);
    }

    private static String component() {
        return component("KLWAP7RT", "420.00");
    }

    private static String component(String basis, String amount) {
        return COMPONENT.write(
                Map.of("basis", basis, "currency", "EUR", "amount", new BigDecimal(amount), "indicator", "AT"));
    }

    private static String tax() {
        return tax("DE", "70.83");
    }

    private static String tax(String code, String amount) {
        return TAX.write(Map.of("code", code, "currency", "EUR", "amount", new BigDecimal(amount)));
    }

    private static String end() {
        return end("490.83");
    }

    private static String end(String amount) {
        return END.write(Map.of("currency", "EUR", "amount", new BigDecimal(amount)));
    }

    private static String payload(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static final String SIMPLE = payload(
            header(),
            offerLine(),
            sector(),
            sector(Map.of(
                    "carrier", "LH",
                    "flight", "0401",
                    "origin", "JFK",
                    "destination", "FRA",
                    "departure", new DayTime(3, 18, 18, 30),
                    "arrival", new DayTime(3, 19, 8, 5),
                    "day_change", 1)),
            component(),
            component("KLWAP7RT", "420.00"),
            tax(),
            tax("YQ", "180.00"),
            end("1090.83"));

    @Test
    void aYearRollStartsWhereItIsTold() {
        YearRoll roll = new YearRoll(2025);
        assertThat(roll.resolve(3, 4)).isEqualTo(LocalDate.of(2025, 3, 4));
        assertThat(roll.resolve(3, 18)).isEqualTo(LocalDate.of(2025, 3, 18));
    }

    @Test
    void aYearRollsWhenTheMonthGoesBackwards() {
        YearRoll roll = new YearRoll(2025);
        assertThat(roll.resolve(12, 28)).isEqualTo(LocalDate.of(2025, 12, 28));
        assertThat(roll.resolve(1, 4)).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    @Test
    void aYearNeverRollsBack() {
        YearRoll roll = new YearRoll(2025);
        roll.resolve(1, 4);
        assertThat(roll.resolve(3, 4)).isEqualTo(LocalDate.of(2025, 3, 4));
    }

    @Test
    void aPayloadFromAnotherSystemIsRefused() {
        String other = payload(HEADER.write(Map.of(
                "source", "NDCFARE",
                "stamp", LocalDate.of(2025, 2, 14),
                "origin", "FRA",
                "destination", "JFK",
                "currency", "EUR")));
        MalformedPayload caught = catchThrowableOfType(MalformedPayload.class, () -> parse(other, SEEN));
        assertThat(caught.details().get("source")).isEqualTo("NDCFARE");
    }

    @Test
    void aPayloadWithNoHeaderIsRefused() {
        assertThatThrownBy(() -> parse(payload(offerLine(), sector()), SEEN)).isInstanceOf(MalformedPayload.class);
    }

    @Test
    void aSimpleResponseGivesOneOffer() {
        List<Offer> offers = parse(SIMPLE, SEEN).offers();
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).source()).isEqualTo("gds");
        assertThat(offers.get(0).origin()).isEqualTo("FRA");
        assertThat(offers.get(0).destination()).isEqualTo("FRA");
    }

    @Test
    void theSectorsComeBackAsAJoinedItinerary() {
        Offer offer = parse(SIMPLE, SEEN).offers().get(0);
        assertThat(offer.itinerary().segments()).hasSize(2);
        assertThat(offer.itinerary().segments().get(0).designator()).isEqualTo("LH400");
        assertThat(offer.itinerary().segments().get(0).departure()).isEqualTo(LocalDateTime.of(2025, 3, 4, 10, 25));
        assertThat(offer.itinerary().segments().get(1).dayChange()).isEqualTo(1);
    }

    @Test
    void theFareComponentsAddIntoTheBase() {
        Offer offer = parse(SIMPLE, SEEN).offers().get(0);
        assertThat(offer.breakdown().base()).isEqualTo(Money.of("840.00", "EUR"));
        assertThat(offer.fareBases()).containsExactly("KLWAP7RT", "KLWAP7RT");
    }

    @Test
    void theTaxesComeAcrossAndYqIsMarkedAsCarrierMoney() {
        Offer offer = parse(SIMPLE, SEEN).offers().get(0);
        assertThat(offer.breakdown().taxTotal()).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(offer.breakdown().surchargeTotal()).isEqualTo(Money.of("180.00", "EUR"));
    }

    @Test
    void theGatewaysOwnTotalIsKeptBesideOurs() {
        Offer offer = parse(SIMPLE, SEEN).offers().get(0);
        assertThat(offer.breakdown().quotedTotal()).contains(Money.of("1090.83", "EUR"));
        assertThat(offer.breakdown().addsUp()).isTrue();
    }

    @Test
    void aTotalThatDisagreesIsKeptAndNotCorrected() {
        String other = SIMPLE.replace(end("1090.83"), end("1200.00"));
        Offer offer = parse(other, SEEN).offers().get(0);
        assertThat(offer.breakdown().quotedTotal()).contains(Money.of("1200.00", "EUR"));
        assertThat(offer.breakdown().addsUp()).isFalse();
    }

    @Test
    void theSeatAndBagCountsComeOffTheOfferRecord() {
        Offer offer = parse(SIMPLE, SEEN).offers().get(0);
        assertThat(offer.seats()).contains(4);
        assertThat(offer.baggage().pieces()).contains(1);
    }

    @Test
    void anOfferRecordWithNoCountsLeavesThemUnstated() {
        String thin =
                payload(header(), OFFER.write(Map.of("reference", "000001")), sector(), component(), end("490.83"));
        Offer offer = parse(thin, SEEN).offers().get(0);
        assertThat(offer.seats()).isEmpty();
        assertThat(offer.baggage().isStated()).isFalse();
    }

    @Test
    void twoOfferRecordsGiveTwoOffers() {
        String two = payload(
                header(),
                offerLine("000001"),
                sector(),
                component(),
                end("490.83"),
                offerLine("000002"),
                sector(Map.of("carrier", "BA", "flight", "0175", "booking_class", "Q")),
                component("QLWAP14RT", "380.00"),
                end("450.83"));
        List<Offer> offers = parse(two, SEEN).offers();
        assertThat(offers).hasSize(2);
        assertThat(offers.get(1).carriers()).containsExactly("BA");
        assertThat(offers.get(1).fareBases()).containsExactly("QLWAP14RT");
    }

    @Test
    void aCodeshareKeepsBothCarriers() {
        String shared = payload(header(), offerLine(), sector(Map.of("operating", "UA")), component(), end("490.83"));
        Segment segment =
                parse(shared, SEEN).offers().get(0).itinerary().segments().get(0);
        assertThat(segment.marketingCarrier()).isEqualTo("LH");
        assertThat(segment.operatedBy()).isEqualTo("UA");
        assertThat(segment.isCodeshare()).isTrue();
    }

    @Test
    void theCabinIsReadOffTheSector() {
        String business = payload(
                header(),
                offerLine(),
                sector(Map.of("cabin", "C", "booking_class", "J")),
                component("JRT", "2900.00"),
                end("2970.83"));
        assertThat(parse(business, SEEN).offers().get(0).cabin()).isSameAs(Cabin.BUSINESS);
    }

    @Test
    void aCabinTheTariffDoesNotPriceFallsBackToEconomy() {
        String odd = payload(header(), offerLine(), sector(Map.of("cabin", "Z")), component(), end("490.83"));
        assertThat(parse(odd, SEEN).offers().get(0).cabin()).isSameAs(Cabin.ECONOMY);
    }

    @Test
    void aBrokenOfferIsDroppedAndTheOthersSurvive() {
        String mixed = payload(
                header(),
                offerLine("000001"),
                sector(Map.of("origin", "FRA", "destination", "JFK")),
                sector(Map.of("origin", "BOS", "destination", "FRA")),
                component(),
                end("490.83"),
                offerLine("000002"),
                sector(),
                component(),
                end("490.83"));
        assertThat(parse(mixed, SEEN).offers()).hasSize(1);
    }

    @Test
    void anOfferWithNoFareComponentIsDropped() {
        String thin = payload(header(), offerLine(), sector(), end("0.00"));
        assertThat(parse(thin, SEEN).offers()).isEmpty();
    }

    @Test
    void recordsBeforeTheFirstOfferRecordAreIgnored() {
        String stray = payload(header(), sector(), offerLine(), sector(), component(), end());
        List<Offer> offers = parse(stray, SEEN).offers();
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).itinerary().segments()).hasSize(1);
    }

    @Test
    void anUnknownRecordIsReportedAndDoesNotStopTheRead() {
        String withJunk = payload(header(), "Z9nonsense", offerLine(), sector(), component(), end());
        assertThat(parse(withJunk, SEEN).offers()).hasSize(1);
        assertThat(parse(withJunk, SEEN).problems()).anyMatch(problem -> problem.contains("unknown record tag"));
    }

    @Test
    void aCleanPayloadReportsNoProblems() {
        assertThat(parse(SIMPLE, SEEN).problems()).isEmpty();
        assertThat(parse(SIMPLE, SEEN).clean()).isTrue();
    }

    @Test
    void aFlightNumberLosesItsPadding() {
        assertThat(parse(SIMPLE, SEEN)
                        .offers()
                        .get(0)
                        .itinerary()
                        .segments()
                        .get(0)
                        .flightNumber())
                .isEqualTo("400");
    }

    @Test
    void theObservationTimeIsOursAndNotThePayloads() {
        assertThat(parse(SIMPLE, SEEN).offers().get(0).observedAt()).isEqualTo(SEEN);
    }

    @Test
    void theProblemsRideAlongOnTheHarvestInsteadOfBeingDropped() {
        // A reader that hands back a bare list of offers leaves its problems visible only to a test.
        String withJunk = payload(header(), "Z9nonsense", offerLine(), sector(), component(), end());
        Harvest harvest = parse(withJunk, SEEN);
        assertThat(harvest.offers()).hasSize(1);
        assertThat(harvest.problems()).hasSize(1);
        assertThat(harvest.clean()).isFalse();
        assertThat(harvest.source()).isEqualTo("gds");
    }

    @Test
    void aGatewayHarvestCarriesNoQuotes() {
        Harvest harvest = parse(SIMPLE, SEEN);
        assertThat(harvest.quotes()).isEmpty();
        assertThat(harvest.cheapestQuote()).isEmpty();
        assertThat(harvest.cheapestOffer()).isPresent();
    }

    @Test
    void anAmountFieldIsTheAmountInCentsPaddedToNineDigits() {
        assertThat(Gds.amountField(new BigDecimal("420.00"))).isEqualTo("000042000");
        assertThat(Gds.amountField(new BigDecimal("70.839"))).isEqualTo("000007083");
        assertThat(Gds.amountField(new BigDecimal("12345678.90"))).isEqualTo("1234567890");
    }
}
