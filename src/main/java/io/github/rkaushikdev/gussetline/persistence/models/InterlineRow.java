package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.Optional;

/** One interline agreement, stored once with the two carriers in sorted order. */
@Entity
@Table(
        name = "interline",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_interline_left_carrier_right_carrier",
                        columnNames = {"left_carrier", "right_carrier"}),
        indexes = {
            @Index(name = "ix_interline_left_carrier", columnList = "left_carrier"),
            @Index(name = "ix_interline_right_carrier", columnList = "right_carrier")
        })
public class InterlineRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "left_carrier", length = 2, nullable = false)
    private String leftCarrier;

    @Column(name = "right_carrier", length = 2, nullable = false)
    private String rightCarrier;

    @Column(name = "signed_on")
    private LocalDate signedOn;

    @Column(name = "source", length = 16, nullable = false)
    private String source = "paper";

    public Integer getId() {
        return id;
    }

    public String getLeftCarrier() {
        return leftCarrier;
    }

    public void setLeftCarrier(String leftCarrier) {
        this.leftCarrier = leftCarrier;
    }

    public String getRightCarrier() {
        return rightCarrier;
    }

    public void setRightCarrier(String rightCarrier) {
        this.rightCarrier = rightCarrier;
    }

    /** When the agreement was signed, if anybody wrote it down. */
    public Optional<LocalDate> getSignedOn() {
        return Optional.ofNullable(signedOn);
    }

    /** Sets the signing date; null for none. */
    public void setSignedOn(LocalDate signedOn) {
        this.signedOn = signedOn;
    }

    /** Where the row came from: {@code paper} unless somebody says otherwise. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** {@code <Interline EK/LH>}. */
    @Override
    public String toString() {
        return "<Interline " + leftCarrier + "/" + rightCarrier + ">";
    }
}
