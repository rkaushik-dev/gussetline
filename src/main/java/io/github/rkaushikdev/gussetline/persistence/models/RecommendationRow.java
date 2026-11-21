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
import java.util.Optional;

/**
 * A reissue the desk was told was worth doing, and what happened to it.
 *
 * <p>Every number is copied rather than joined, because this row has to still read the same in a
 * year when the observation behind it has rolled off. The state has no index of its own: the
 * convention would name it {@code ix_recommendations_state}, which is the name the index on the
 * state and the day it was raised already has.
 */
@Entity
@Table(
        name = "recommendations",
        indexes = {
            @Index(name = "ix_recommendations_watch_id", columnList = "watch_id"),
            @Index(name = "ix_recommendations_raised_on", columnList = "raised_on"),
            @Index(name = "ix_recommendations_state", columnList = "state, raised_on")
        })
public class RecommendationRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "watch_id", nullable = false)
    private Integer watchId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "watch_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_recommendations_watch_id_watches"))
    private WatchRow watch;

    @Column(name = "raised_on", nullable = false)
    private LocalDate raisedOn;

    @Column(name = "state", length = 16, nullable = false)
    private String state = "open";

    @Convert(converter = Amount.class)
    @Column(name = "paid_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal paidAmount;

    @Convert(converter = Amount.class)
    @Column(name = "new_fare_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal newFareAmount;

    @Convert(converter = Amount.class)
    @Column(name = "penalty_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal penaltyAmount;

    @Convert(converter = Amount.class)
    @Column(name = "benefit_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal benefitAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "residual", length = 16, nullable = false)
    private String residual = "forfeited";

    @Column(name = "fare_bases", length = 128, nullable = false)
    private String fareBases = "";

    @Column(name = "observation_fingerprint", length = 32, nullable = false)
    private String observationFingerprint = "";

    @Column(name = "decided_on")
    private LocalDate decidedOn;

    @Column(name = "decided_by", length = 32, nullable = false)
    private String decidedBy = "";

    @Column(name = "note", length = 256, nullable = false)
    private String note = "";

    public Integer getId() {
        return id;
    }

    /** The watch that raised it; set from the watch when added through it. */
    public Integer getWatchId() {
        return watchId;
    }

    public void setWatchId(Integer watchId) {
        this.watchId = watchId;
    }

    /** The watch that raised it, once added through it or read back. */
    public WatchRow getWatch() {
        return watch;
    }

    void setWatch(WatchRow watch) {
        this.watch = watch;
        this.watchId = watch.getId();
    }

    @PrePersist
    void followWatch() {
        if (watch != null && watch.getId() != null) {
            watchId = watch.getId();
        }
    }

    public LocalDate getRaisedOn() {
        return raisedOn;
    }

    public void setRaisedOn(LocalDate raisedOn) {
        this.raisedOn = raisedOn;
    }

    /** Where the recommendation stands: {@code open} until somebody decides. */
    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getNewFareAmount() {
        return newFareAmount;
    }

    public void setNewFareAmount(BigDecimal newFareAmount) {
        this.newFareAmount = newFareAmount;
    }

    public BigDecimal getPenaltyAmount() {
        return penaltyAmount;
    }

    public void setPenaltyAmount(BigDecimal penaltyAmount) {
        this.penaltyAmount = penaltyAmount;
    }

    public BigDecimal getBenefitAmount() {
        return benefitAmount;
    }

    public void setBenefitAmount(BigDecimal benefitAmount) {
        this.benefitAmount = benefitAmount;
    }

    /** The currency every amount on the row is in. */
    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    /** What becomes of the unused value: {@code forfeited} unless the rule says otherwise. */
    public String getResidual() {
        return residual;
    }

    public void setResidual(String residual) {
        this.residual = residual;
    }

    public String getFareBases() {
        return fareBases;
    }

    public void setFareBases(String fareBases) {
        this.fareBases = fareBases;
    }

    /** The fingerprint of the observation it was raised on, or empty text. */
    public String getObservationFingerprint() {
        return observationFingerprint;
    }

    public void setObservationFingerprint(String observationFingerprint) {
        this.observationFingerprint = observationFingerprint;
    }

    public Optional<LocalDate> getDecidedOn() {
        return Optional.ofNullable(decidedOn);
    }

    /** Sets the day it was decided; null while it is undecided. */
    public void setDecidedOn(LocalDate decidedOn) {
        this.decidedOn = decidedOn;
    }

    /** Who decided, or empty text while it is undecided. */
    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /** {@code <Recommendation open worth 170.00 EUR>}. */
    @Override
    public String toString() {
        return "<Recommendation " + state + " worth " + benefitAmount + " " + currency + ">";
    }
}
