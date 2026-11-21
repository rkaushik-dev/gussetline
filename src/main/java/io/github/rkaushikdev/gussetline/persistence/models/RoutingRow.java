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
import java.util.Optional;

/** One permitted hop inside a routing map; a map is every row under one carrier and number. */
@Entity
@Table(
        name = "routings",
        indexes = {
            @Index(name = "ix_routings_load_id", columnList = "load_id"),
            @Index(name = "ix_routings_map", columnList = "carrier, number")
        })
public class RoutingRow extends Timestamped {

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
            foreignKey = @ForeignKey(name = "fk_routings_load_id_tariff_loads"))
    private LoadRow load;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "number", length = 8, nullable = false)
    private String number;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "carriers", length = 64, nullable = false)
    private String carriers = "";

    @Column(name = "bidirectional", nullable = false)
    private boolean bidirectional = true;

    public Integer getId() {
        return id;
    }

    /** The load the hop came in on, if it came in on one. */
    public Optional<Integer> getLoadId() {
        return Optional.ofNullable(loadId);
    }

    /** Sets the load the hop came in on; null for none. */
    public void setLoadId(Integer loadId) {
        this.loadId = loadId;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
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

    /** The carriers that may fly the hop, as text: {@code LH/UA}, or empty for any. */
    public String getCarriers() {
        return carriers;
    }

    public void setCarriers(String carriers) {
        this.carriers = carriers;
    }

    /** Whether the hop may be flown either way, which is the default. */
    public boolean isBidirectional() {
        return bidirectional;
    }

    public void setBidirectional(boolean bidirectional) {
        this.bidirectional = bidirectional;
    }

    /** {@code <Routing LH 0101 FRA-NYC>}. */
    @Override
    public String toString() {
        return "<Routing " + carrier + " " + number + " " + origin + "-" + destination + ">";
    }
}
