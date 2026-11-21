package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One priced itinerary as one supplier described it, with its sectors and its taxes.
 *
 * <p>The same offer seen again in the same series is not a second row: the fingerprint is unique
 * per series, and a repeat sighting moves {@code last_seen} and counts one more sighting.
 */
@Entity
@Table(
        name = "observations",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_observations_series_key_fingerprint",
                        columnNames = {"series_key", "fingerprint"}),
        indexes = {
            @Index(name = "ix_observations_series_key", columnList = "series_key"),
            @Index(name = "ix_observations_fingerprint", columnList = "fingerprint"),
            @Index(name = "ix_observations_source", columnList = "source"),
            @Index(name = "ix_observations_total_amount", columnList = "total_amount"),
            @Index(name = "ix_observations_raw_digest", columnList = "raw_digest"),
            @Index(name = "ix_observations_last_seen", columnList = "last_seen"),
            @Index(name = "ix_observations_series_seen", columnList = "series_key, last_seen"),
            @Index(name = "ix_observations_market", columnList = "origin, destination, departure")
        })
public class ObservationRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "series_key", length = 48, nullable = false)
    private String seriesKey;

    @Column(name = "fingerprint", length = 32, nullable = false)
    private String fingerprint;

    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "cabin", length = 1, nullable = false)
    private String cabin = "Y";

    @Column(name = "fare_bases", length = 128, nullable = false)
    private String fareBases = "";

    @Convert(converter = Amount.class)
    @Column(name = "base_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "base_currency", length = 3, nullable = false)
    private String baseCurrency;

    @Convert(converter = Amount.class)
    @Column(name = "tax_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal taxAmount;

    @Convert(converter = Amount.class)
    @Column(name = "surcharge_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal surchargeAmount;

    @Convert(converter = Amount.class)
    @Column(name = "total_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal totalAmount;

    @Convert(converter = Amount.class)
    @Column(name = "quoted_total", length = Amount.LENGTH)
    private BigDecimal quotedTotal;

    @Column(name = "seats")
    private Integer seats;

    @Column(name = "bag_pieces")
    private Integer bagPieces;

    @Column(name = "refundable")
    private Boolean refundable;

    @Column(name = "validating_carrier", length = 2, nullable = false)
    private String validatingCarrier = "";

    @Column(name = "raw_digest", length = 32, nullable = false)
    private String rawDigest = "";

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    @Column(name = "sightings", nullable = false)
    private int sightings = 1;

    @OneToMany(mappedBy = "observation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<SectorRow> sectors = new ArrayList<>();

    @OneToMany(mappedBy = "observation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaxRow> taxes = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    /** The series the observation is grouped under: {@code FRA-NYC-2025-07-04-Y}. */
    public String getSeriesKey() {
        return seriesKey;
    }

    public void setSeriesKey(String seriesKey) {
        this.seriesKey = seriesKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public String getCabin() {
        return cabin;
    }

    public void setCabin(String cabin) {
        this.cabin = cabin;
    }

    /** The fare bases, joined by {@code /}. */
    public String getFareBases() {
        return fareBases;
    }

    public void setFareBases(String fareBases) {
        this.fareBases = fareBases;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }

    /** The currency every amount on the row is in. */
    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getSurchargeAmount() {
        return surchargeAmount;
    }

    public void setSurchargeAmount(BigDecimal surchargeAmount) {
        this.surchargeAmount = surchargeAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    /** The total the supplier stated, kept beside ours when it said one. */
    public Optional<BigDecimal> getQuotedTotal() {
        return Optional.ofNullable(quotedTotal);
    }

    /** Sets the stated total; null for none. */
    public void setQuotedTotal(BigDecimal quotedTotal) {
        this.quotedTotal = quotedTotal;
    }

    public Optional<Integer> getSeats() {
        return Optional.ofNullable(seats);
    }

    /** Sets the seats left; null when the supplier did not say. */
    public void setSeats(Integer seats) {
        this.seats = seats;
    }

    public Optional<Integer> getBagPieces() {
        return Optional.ofNullable(bagPieces);
    }

    /** Sets the bag allowance in pieces; null when the supplier did not say. */
    public void setBagPieces(Integer bagPieces) {
        this.bagPieces = bagPieces;
    }

    public Optional<Boolean> getRefundable() {
        return Optional.ofNullable(refundable);
    }

    /** Sets whether the fare is refundable; null when the supplier did not say. */
    public void setRefundable(Boolean refundable) {
        this.refundable = refundable;
    }

    /** The validating carrier, or empty text when the supplier did not say. */
    public String getValidatingCarrier() {
        return validatingCarrier;
    }

    public void setValidatingCarrier(String validatingCarrier) {
        this.validatingCarrier = validatingCarrier;
    }

    /** The digest of the payload the offer was read from, or empty text. */
    public String getRawDigest() {
        return rawDigest;
    }

    public void setRawDigest(String rawDigest) {
        this.rawDigest = rawDigest;
    }

    public LocalDateTime getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(LocalDateTime firstSeen) {
        this.firstSeen = firstSeen;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getSightings() {
        return sightings;
    }

    public void setSightings(int sightings) {
        this.sightings = sightings;
    }

    /** The sectors, in position order once read back; read only. */
    public List<SectorRow> getSectors() {
        return Collections.unmodifiableList(sectors);
    }

    public void addSector(SectorRow sector) {
        sector.setObservation(this);
        sectors.add(sector);
    }

    /** Replace every sector, deleting the ones not given again. */
    public void setSectors(List<SectorRow> replacement) {
        List<SectorRow> kept = List.copyOf(replacement);
        sectors.clear();
        kept.forEach(this::addSector);
    }

    /** The taxes and carrier surcharges; read only. */
    public List<TaxRow> getTaxes() {
        return Collections.unmodifiableList(taxes);
    }

    public void addTax(TaxRow tax) {
        tax.setObservation(this);
        taxes.add(tax);
    }

    /** Replace every tax, deleting the ones not given again. */
    public void setTaxes(List<TaxRow> replacement) {
        List<TaxRow> kept = List.copyOf(replacement);
        taxes.clear();
        kept.forEach(this::addTax);
    }

    /** {@code <Observation FRA-NYC-2025-07-04-Y 670.83 EUR>}. */
    @Override
    public String toString() {
        return "<Observation " + seriesKey + " " + totalAmount + " " + baseCurrency + ">";
    }
}
