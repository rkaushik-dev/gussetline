package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
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

/** One tax or carrier surcharge on an observed price. */
@Entity
@Table(
        name = "observation_taxes",
        indexes = @Index(name = "ix_observation_taxes_observation_id", columnList = "observation_id"))
public class TaxRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "observation_id", nullable = false)
    private Integer observationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "observation_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_observation_taxes_observation_id_observations"))
    private ObservationRow observation;

    @Column(name = "code", length = 3, nullable = false)
    private String code;

    @Convert(converter = Amount.class)
    @Column(name = "amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "point", length = 3, nullable = false)
    private String point = "";

    @Column(name = "carrier_imposed", nullable = false)
    private boolean carrierImposed;

    public Integer getId() {
        return id;
    }

    /** The observation the tax belongs to; set from it when the tax is added through it. */
    public Integer getObservationId() {
        return observationId;
    }

    public void setObservationId(Integer observationId) {
        this.observationId = observationId;
    }

    /** The observation the tax belongs to, once added through one or read back. */
    public ObservationRow getObservation() {
        return observation;
    }

    void setObservation(ObservationRow observation) {
        this.observation = observation;
        this.observationId = observation.getId();
    }

    @PrePersist
    void followObservation() {
        if (observation != null && observation.getId() != null) {
            observationId = observation.getId();
        }
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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

    /** Where the tax is levied, or empty text. */
    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }

    /** Whether the carrier imposed it, as against a government. */
    public boolean isCarrierImposed() {
        return carrierImposed;
    }

    public void setCarrierImposed(boolean carrierImposed) {
        this.carrierImposed = carrierImposed;
    }

    /** {@code <Tax DE 70.83>}. */
    @Override
    public String toString() {
        return "<Tax " + code + " " + amount + ">";
    }
}
