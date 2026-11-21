package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The rows the route tests seed: one ticket out of Frankfurt, a watch on it, and what the watch raised. */
final class Rows {

    private Rows() {}

    /** Anna Mueller's ticket to New York, paid 840.00 EUR, departing on the day given. */
    static TicketRow ticket(LocalDate departure) {
        TicketRow ticket = new TicketRow();
        ticket.setNumber("220-1234567890");
        ticket.setRecordLocator("ABC123");
        ticket.setPassengerName("MUELLER/ANNA");
        ticket.setOrigin("FRA");
        ticket.setDestination("JFK");
        ticket.setDeparture(departure);
        ticket.setIssuedOn(LocalDate.of(2025, 2, 14));
        ticket.setValidatingCarrier("LH");
        ticket.setBaseAmount(new BigDecimal("840.00"));
        ticket.setBaseCurrency("EUR");
        ticket.setTotalAmount(new BigDecimal("1090.83"));
        return ticket;
    }

    /** A live watch on the ticket's market, with a 50.00 EUR threshold. */
    static WatchRow watch(String seriesKey, LocalDate departure) {
        WatchRow watch = new WatchRow();
        watch.setSeriesKey(seriesKey);
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(departure);
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        return watch;
    }

    /** A recommendation to reissue at 700.00 for a 50.00 penalty, worth the benefit given. */
    static RecommendationRow recommendation(LocalDate raised, String state, String benefit) {
        RecommendationRow made = new RecommendationRow();
        made.setRaisedOn(raised);
        made.setState(state);
        made.setPaidAmount(new BigDecimal("840.00"));
        made.setNewFareAmount(new BigDecimal("700.00"));
        made.setPenaltyAmount(new BigDecimal("50.00"));
        made.setBenefitAmount(new BigDecimal(benefit));
        made.setCurrency("EUR");
        return made;
    }
}
