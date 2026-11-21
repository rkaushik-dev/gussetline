package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** A calendar quote: a price for a market and a day, which has no flights and cannot be audited. */
@Entity
@Table(
        name = "quotes",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_quotes_origin_destination_departure_observed_on",
                        columnNames = {"origin", "destination", "departure", "observed_on"}),
        indexes = {
            @Index(name = "ix_quotes_observed_on", columnList = "observed_on"),
            @Index(name = "ix_quotes_market", columnList = "origin, destination, departure")
        })
public class QuoteRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "observed_on", nullable = false)
    private LocalDate observedOn;

    @Convert(converter = Amount.class)
    @Column(name = "amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier = "";

    @Column(name = "seats")
    private Integer seats;

    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    @Column(name = "raw_digest", length = 32, nullable = false)
    private String rawDigest = "";

    public Integer getId() {
        return id;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public LocalDate getDeparture() {
        return departure;
    }

    public void setDeparture(LocalDate departure) {
        this.departure = departure;
    }

    /** The day the quote was read. */
    public LocalDate getObservedOn() {
        return observedOn;
    }

    public void setObservedOn(LocalDate observedOn) {
        this.observedOn = observedOn;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    /** The carrier quoted, or empty text when the calendar did not say. */
    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public Optional<Integer> getSeats() {
        return Optional.ofNullable(seats);
    }

    /** Sets the seats left; null when the calendar did not say. */
    public void setSeats(Integer seats) {
        this.seats = seats;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public String getRawDigest() {
        return rawDigest;
    }

    public void setRawDigest(String rawDigest) {
        this.rawDigest = rawDigest;
    }

    /** {@code <Quote FRANYC 2025-07-04 420.00>}. */
    @Override
    public String toString() {
        return "<Quote " + origin + destination + " " + departure + " " + amount + ">";
    }
}
