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

/** A published ticketed point mileage, keyed the way the tariff keys it. */
@Entity
@Table(
        name = "mileages",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_mileages_origin_destination_indicator",
                        columnNames = {"origin", "destination", "indicator"}),
        indexes = {
            @Index(name = "ix_mileages_origin", columnList = "origin"),
            @Index(name = "ix_mileages_destination", columnList = "destination")
        })
public class MileageRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "indicator", length = 2, nullable = false)
    private String indicator = "";

    @Column(name = "miles", nullable = false)
    private int miles;

    @Column(name = "source", length = 16, nullable = false)
    private String source = "published";

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

    /** The global indicator the mileage is published under, or empty text for none. */
    public String getIndicator() {
        return indicator;
    }

    public void setIndicator(String indicator) {
        this.indicator = indicator;
    }

    public int getMiles() {
        return miles;
    }

    public void setMiles(int miles) {
        this.miles = miles;
    }

    /** Where the row came from: {@code published} unless somebody says otherwise. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** {@code <Mileage FRA-TYO 5928>}. */
    @Override
    public String toString() {
        return "<Mileage " + origin + "-" + destination + " " + miles + ">";
    }
}
