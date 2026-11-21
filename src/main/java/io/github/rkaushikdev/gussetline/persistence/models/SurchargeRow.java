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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** A filed Q surcharge on one market. */
@Entity
@Table(
        name = "surcharges",
        indexes = {
            @Index(name = "ix_surcharges_load_id", columnList = "load_id"),
            @Index(name = "ix_surcharges_market", columnList = "carrier, origin, destination")
        })
public class SurchargeRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "load_id")
    private Integer loadId;

    /** Declares the foreign key on {@code load_id}, which is written through the column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "load_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_surcharges_load_id_tariff_loads"))
    private LoadRow load;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Convert(converter = Amount.class)
    @Column(name = "amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "scope", length = 16, nullable = false)
    private String scope = "component";

    @Column(name = "reason", length = 4, nullable = false)
    private String reason = "Q";

    @Column(name = "directional", nullable = false)
    private boolean directional;

    @Column(name = "first_travel", nullable = false)
    private LocalDate firstTravel;

    @Column(name = "last_travel", nullable = false)
    private LocalDate lastTravel;

    public Integer getId() {
        return id;
    }

    /** The load the surcharge came in on, if it came in on one. */
    public Optional<Integer> getLoadId() {
        return Optional.ofNullable(loadId);
    }

    /** Sets the load the surcharge came in on; null for none. */
    public void setLoadId(Integer loadId) {
        this.loadId = loadId;
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

    /** What the surcharge is levied on: {@code component} unless filed otherwise. */
    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isDirectional() {
        return directional;
    }

    public void setDirectional(boolean directional) {
        this.directional = directional;
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

    /** {@code <Surcharge Q FRANYC>}. */
    @Override
    public String toString() {
        return "<Surcharge " + reason + " " + origin + destination + ">";
    }
}
