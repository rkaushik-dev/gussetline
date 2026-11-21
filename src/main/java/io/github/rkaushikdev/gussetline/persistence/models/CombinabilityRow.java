package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import java.util.Optional;

/** What one fare may be joined to. A row that says nothing permits nothing. */
@Entity
@Table(
        name = "combinability",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_combinability_carrier_fare_basis",
                        columnNames = {"carrier", "fare_basis"}),
        indexes = {
            @Index(name = "ix_combinability_load_id", columnList = "load_id"),
            @Index(name = "ix_combinability_carrier", columnList = "carrier"),
            @Index(name = "ix_combinability_fare_basis", columnList = "fare_basis")
        })
public class CombinabilityRow extends Timestamped {

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
            foreignKey = @ForeignKey(name = "fk_combinability_load_id_tariff_loads"))
    private LoadRow load;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "fare_basis", length = 16, nullable = false)
    private String fareBasis;

    @Column(name = "round_trip", nullable = false)
    private boolean roundTrip;

    @Column(name = "circle_trip", nullable = false)
    private boolean circleTrip;

    @Column(name = "open_jaw", nullable = false)
    private boolean openJaw;

    @Column(name = "double_open_jaw", nullable = false)
    private boolean doubleOpenJaw;

    @Column(name = "end_on_end", nullable = false)
    private boolean endOnEnd;

    @Column(name = "same_carrier_only", nullable = false)
    private boolean sameCarrierOnly = true;

    @Column(name = "same_rule_only", nullable = false)
    private boolean sameRuleOnly;

    @Column(name = "side_trip", nullable = false)
    private boolean sideTrip;

    public Integer getId() {
        return id;
    }

    /** The load the record came in on, if it came in on one. */
    public Optional<Integer> getLoadId() {
        return Optional.ofNullable(loadId);
    }

    /** Sets the load the record came in on; null for none. */
    public void setLoadId(Integer loadId) {
        this.loadId = loadId;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getFareBasis() {
        return fareBasis;
    }

    public void setFareBasis(String fareBasis) {
        this.fareBasis = fareBasis;
    }

    public boolean isRoundTrip() {
        return roundTrip;
    }

    public void setRoundTrip(boolean roundTrip) {
        this.roundTrip = roundTrip;
    }

    public boolean isCircleTrip() {
        return circleTrip;
    }

    public void setCircleTrip(boolean circleTrip) {
        this.circleTrip = circleTrip;
    }

    public boolean isOpenJaw() {
        return openJaw;
    }

    public void setOpenJaw(boolean openJaw) {
        this.openJaw = openJaw;
    }

    public boolean isDoubleOpenJaw() {
        return doubleOpenJaw;
    }

    public void setDoubleOpenJaw(boolean doubleOpenJaw) {
        this.doubleOpenJaw = doubleOpenJaw;
    }

    public boolean isEndOnEnd() {
        return endOnEnd;
    }

    public void setEndOnEnd(boolean endOnEnd) {
        this.endOnEnd = endOnEnd;
    }

    /** Whether the fare may be joined only to its own carrier's fares, which is the default. */
    public boolean isSameCarrierOnly() {
        return sameCarrierOnly;
    }

    public void setSameCarrierOnly(boolean sameCarrierOnly) {
        this.sameCarrierOnly = sameCarrierOnly;
    }

    public boolean isSameRuleOnly() {
        return sameRuleOnly;
    }

    public void setSameRuleOnly(boolean sameRuleOnly) {
        this.sameRuleOnly = sameRuleOnly;
    }

    public boolean isSideTrip() {
        return sideTrip;
    }

    public void setSideTrip(boolean sideTrip) {
        this.sideTrip = sideTrip;
    }

    /** {@code <Combinability LH KLWAP7RT>}. */
    @Override
    public String toString() {
        return "<Combinability " + carrier + " " + fareBasis + ">";
    }
}
