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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One issued ticket as the desk holds it, with its coupons and the watches on it.
 *
 * <p>A watch goes with its ticket. Everything that reads a watch reads the ticket through it, so one
 * left behind is a row nothing can load, and the night run trips over it rather than skipping it.
 */
@Entity
@Table(
        name = "tickets",
        uniqueConstraints = @UniqueConstraint(name = "uq_tickets_number", columnNames = "number"),
        indexes = {
            @Index(name = "ix_tickets_record_locator", columnList = "record_locator"),
            @Index(name = "ix_tickets_departure", columnList = "departure"),
            @Index(name = "ix_tickets_issued_on", columnList = "issued_on"),
            @Index(name = "ix_tickets_market", columnList = "origin, destination, departure")
        })
public class TicketRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "number", length = 16, nullable = false)
    private String number;

    @Column(name = "record_locator", length = 8, nullable = false)
    private String recordLocator;

    @Column(name = "passenger_name", length = 64, nullable = false)
    private String passengerName;

    @Column(name = "passenger_type", length = 3, nullable = false)
    private String passengerType = "ADT";

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "validating_carrier", length = 2, nullable = false)
    private String validatingCarrier;

    @Column(name = "fare_calculation", length = 512, nullable = false)
    private String fareCalculation = "";

    @Convert(converter = Amount.class)
    @Column(name = "base_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "base_currency", length = 3, nullable = false)
    private String baseCurrency;

    @Convert(converter = Amount.class)
    @Column(name = "total_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "country_of_sale", length = 2, nullable = false)
    private String countryOfSale = "";

    @Column(name = "void", nullable = false)
    private boolean voided;

    @Column(name = "reissued_from", length = 16, nullable = false)
    private String reissuedFrom = "";

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<CouponRow> coupons = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WatchRow> watches = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getRecordLocator() {
        return recordLocator;
    }

    public void setRecordLocator(String recordLocator) {
        this.recordLocator = recordLocator;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    /** The passenger type code: {@code ADT} unless the ticket says otherwise. */
    public String getPassengerType() {
        return passengerType;
    }

    public void setPassengerType(String passengerType) {
        this.passengerType = passengerType;
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

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public void setIssuedOn(LocalDate issuedOn) {
        this.issuedOn = issuedOn;
    }

    public String getValidatingCarrier() {
        return validatingCarrier;
    }

    public void setValidatingCarrier(String validatingCarrier) {
        this.validatingCarrier = validatingCarrier;
    }

    public String getFareCalculation() {
        return fareCalculation;
    }

    public void setFareCalculation(String fareCalculation) {
        this.fareCalculation = fareCalculation;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }

    /** The currency the base and the total are in. */
    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    /** The country the ticket was sold in, or empty text. */
    public String getCountryOfSale() {
        return countryOfSale;
    }

    public void setCountryOfSale(String countryOfSale) {
        this.countryOfSale = countryOfSale;
    }

    /** Whether the ticket was voided, which the {@code void} column holds. */
    public boolean isVoid() {
        return voided;
    }

    public void setVoid(boolean voided) {
        this.voided = voided;
    }

    /** The number of the ticket this one was reissued from, or empty text for an original issue. */
    public String getReissuedFrom() {
        return reissuedFrom;
    }

    public void setReissuedFrom(String reissuedFrom) {
        this.reissuedFrom = reissuedFrom;
    }

    /** The coupons, in position order once read back; read only. */
    public List<CouponRow> getCoupons() {
        return Collections.unmodifiableList(coupons);
    }

    public void addCoupon(CouponRow coupon) {
        coupon.setTicket(this);
        coupons.add(coupon);
    }

    /** Take a coupon off the ticket, which deletes it. */
    public void removeCoupon(CouponRow coupon) {
        coupons.remove(coupon);
    }

    /** Replace every coupon, deleting the ones not given again. */
    public void setCoupons(List<CouponRow> replacement) {
        List<CouponRow> kept = List.copyOf(replacement);
        coupons.clear();
        kept.forEach(this::addCoupon);
    }

    /** The watches on the ticket; read only. */
    public List<WatchRow> getWatches() {
        return Collections.unmodifiableList(watches);
    }

    public void addWatch(WatchRow watch) {
        watch.setTicket(this);
        watches.add(watch);
    }

    /** Take a watch off the ticket, which deletes it and its recommendations. */
    public void removeWatch(WatchRow watch) {
        watches.remove(watch);
    }

    /** {@code <Ticket 220-1234567890 FRAJFK>}. */
    @Override
    public String toString() {
        return "<Ticket " + number + " " + origin + destination + ">";
    }
}
