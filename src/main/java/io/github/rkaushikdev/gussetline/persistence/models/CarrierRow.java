package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One carrier, keyed by its designator, with its alliance and whether it is low cost. */
@Entity
@Table(name = "carriers")
public class CarrierRow extends Timestamped {

    @Id
    @Column(name = "code", length = 2, nullable = false)
    private String code;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "country", length = 2, nullable = false)
    private String country;

    @Column(name = "alliance", length = 16, nullable = false)
    private String alliance = "none";

    @Column(name = "low_cost", nullable = false)
    private boolean lowCost;

    @Column(name = "source", length = 16, nullable = false)
    private String source = "shipped";

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    /** The alliance's value, {@code none} for an unaligned carrier. */
    public String getAlliance() {
        return alliance;
    }

    public void setAlliance(String alliance) {
        this.alliance = alliance;
    }

    public boolean isLowCost() {
        return lowCost;
    }

    public void setLowCost(boolean lowCost) {
        this.lowCost = lowCost;
    }

    /** Where the row came from: {@code shipped} unless somebody says otherwise. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** {@code <Carrier LH>}. */
    @Override
    public String toString() {
        return "<Carrier " + code + ">";
    }
}
