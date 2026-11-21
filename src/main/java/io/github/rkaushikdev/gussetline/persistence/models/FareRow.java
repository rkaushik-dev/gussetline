package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One published fare as the tariff filed it, under the load it came in on.
 *
 * <p>Three indexes: the market, the market from a date, and the fare basis. The night run gets from
 * a ticket's fare basis to its rule and has no market to narrow on; without the basis index it takes
 * the market index for its carrier prefix alone, which on a real tariff is most of the table read to
 * find one row.
 */
@Entity
@Table(
        name = "fares",
        indexes = {
            @Index(name = "ix_fares_load_id", columnList = "load_id"),
            @Index(name = "ix_fares_market", columnList = "carrier, origin, destination"),
            @Index(name = "ix_fares_lookup", columnList = "carrier, origin, destination, effective"),
            @Index(name = "ix_fares_basis", columnList = "carrier, fare_basis")
        })
public class FareRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "load_id", nullable = false)
    private Integer loadId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "load_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_fares_load_id_tariff_loads"))
    private LoadRow load;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "fare_basis", length = 16, nullable = false)
    private String fareBasis;

    @Convert(converter = Amount.class)
    @Column(name = "amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "owrt", length = 1, nullable = false)
    private String owrt;

    @Column(name = "global_indicator", length = 2, nullable = false)
    private String globalIndicator;

    @Column(name = "rule_number", length = 8, nullable = false)
    private String ruleNumber;

    @Column(name = "routing_number", length = 8, nullable = false)
    private String routingNumber;

    @Column(name = "tariff_number", length = 4, nullable = false)
    private String tariffNumber = "1";

    @Column(name = "footnote", length = 4, nullable = false)
    private String footnote = "";

    @Column(name = "cabin", length = 1, nullable = false)
    private String cabin = "Y";

    @Column(name = "fare_type", length = 16, nullable = false)
    private String fareType = "published";

    @Column(name = "effective", nullable = false)
    private LocalDate effective;

    @Column(name = "discontinue", nullable = false)
    private LocalDate discontinue;

    @Column(name = "first_travel", nullable = false)
    private LocalDate firstTravel;

    @Column(name = "last_travel", nullable = false)
    private LocalDate lastTravel;

    public Integer getId() {
        return id;
    }

    /** The load the fare was filed in; set from the load itself when the fare is filed through it. */
    public Integer getLoadId() {
        return loadId;
    }

    public void setLoadId(Integer loadId) {
        this.loadId = loadId;
    }

    /** The load the fare was filed in, once it is filed through one or read back. */
    public LoadRow getLoad() {
        return load;
    }

    void setLoad(LoadRow load) {
        this.load = load;
        this.loadId = load.getId();
    }

    @PrePersist
    void followLoad() {
        if (load != null && load.getId() != null) {
            loadId = load.getId();
        }
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
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

    public String getFareBasis() {
        return fareBasis;
    }

    public void setFareBasis(String fareBasis) {
        this.fareBasis = fareBasis;
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

    /** The one way or round trip indicator's value. */
    public String getOwrt() {
        return owrt;
    }

    public void setOwrt(String owrt) {
        this.owrt = owrt;
    }

    public String getGlobalIndicator() {
        return globalIndicator;
    }

    public void setGlobalIndicator(String globalIndicator) {
        this.globalIndicator = globalIndicator;
    }

    public String getRuleNumber() {
        return ruleNumber;
    }

    public void setRuleNumber(String ruleNumber) {
        this.ruleNumber = ruleNumber;
    }

    public String getRoutingNumber() {
        return routingNumber;
    }

    public void setRoutingNumber(String routingNumber) {
        this.routingNumber = routingNumber;
    }

    public String getTariffNumber() {
        return tariffNumber;
    }

    public void setTariffNumber(String tariffNumber) {
        this.tariffNumber = tariffNumber;
    }

    public String getFootnote() {
        return footnote;
    }

    public void setFootnote(String footnote) {
        this.footnote = footnote;
    }

    /** The cabin's one letter value. */
    public String getCabin() {
        return cabin;
    }

    public void setCabin(String cabin) {
        this.cabin = cabin;
    }

    public String getFareType() {
        return fareType;
    }

    public void setFareType(String fareType) {
        this.fareType = fareType;
    }

    public LocalDate getEffective() {
        return effective;
    }

    public void setEffective(LocalDate effective) {
        this.effective = effective;
    }

    public LocalDate getDiscontinue() {
        return discontinue;
    }

    public void setDiscontinue(LocalDate discontinue) {
        this.discontinue = discontinue;
    }

    public LocalDate getFirstTravel() {
        return firstTravel;
    }

    public void setFirstTravel(LocalDate firstTravel) {
        this.firstTravel = firstTravel;
    }

    public LocalDate getLastTravel() {
        return lastTravel;
    }

    public void setLastTravel(LocalDate lastTravel) {
        this.lastTravel = lastTravel;
    }

    /** {@code <Fare LH FRANYC KLWAP7RT>}. */
    @Override
    public String toString() {
        return "<Fare " + carrier + " " + origin + destination + " " + fareBasis + ">";
    }
}
