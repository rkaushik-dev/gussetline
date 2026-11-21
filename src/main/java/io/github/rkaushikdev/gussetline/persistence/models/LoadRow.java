package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One tariff file, when it was taken in, and the fares it filed, which go when it does. */
@Entity
@Table(
        name = "tariff_loads",
        indexes = {
            @Index(name = "ix_tariff_loads_carrier", columnList = "carrier"),
            @Index(name = "ix_tariff_loads_digest", columnList = "digest"),
            @Index(name = "ix_tariff_loads_loaded_at", columnList = "loaded_at"),
            @Index(name = "ix_tariff_loads_effective_from", columnList = "effective_from"),
            @Index(name = "ix_tariff_loads_retired", columnList = "retired")
        })
public class LoadRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "filename", length = 128, nullable = false)
    private String filename;

    @Column(name = "digest", length = 32, nullable = false)
    private String digest;

    @Column(name = "loaded_at", nullable = false)
    private LocalDateTime loadedAt;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "retired", nullable = false)
    private boolean retired;

    @OneToMany(mappedBy = "load", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FareRow> fares = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    /** When the file was taken in, in UTC. */
    public LocalDateTime getLoadedAt() {
        return loadedAt;
    }

    public void setLoadedAt(LocalDateTime loadedAt) {
        this.loadedAt = loadedAt;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public boolean isRetired() {
        return retired;
    }

    public void setRetired(boolean retired) {
        this.retired = retired;
    }

    /** The fares this file filed, read only; change them through the methods below. */
    public List<FareRow> getFares() {
        return Collections.unmodifiableList(fares);
    }

    /** File one more fare under this load. */
    public void addFare(FareRow fare) {
        fare.setLoad(this);
        fares.add(fare);
    }

    /** Take a fare off this load, which deletes it. */
    public void removeFare(FareRow fare) {
        fares.remove(fare);
    }

    /** Replace every fare, deleting the ones not given again. */
    public void setFares(List<FareRow> replacement) {
        List<FareRow> kept = List.copyOf(replacement);
        fares.clear();
        kept.forEach(this::addFare);
    }

    /** {@code <Load LH LH-20250603.dat 2 rows>}. */
    @Override
    public String toString() {
        return "<Load " + carrier + " " + filename + " " + rowCount + " rows>";
    }
}
