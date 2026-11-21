package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One currency's rate of exchange from one date, in one of the two rate tables. */
@Entity
@Table(
        name = "rates",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_rates_table_name_currency_effective",
                        columnNames = {"table_name", "currency", "effective"}),
        indexes = {
            @Index(name = "ix_rates_table_name", columnList = "table_name"),
            @Index(name = "ix_rates_currency", columnList = "currency"),
            @Index(name = "ix_rates_effective", columnList = "effective")
        })
public class RateRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "table_name", length = 8, nullable = false)
    private String tableName;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "effective", nullable = false)
    private LocalDate effective;

    @Convert(converter = Amount.class)
    @Column(name = "units_per_nuc", length = Amount.LENGTH, nullable = false)
    private BigDecimal unitsPerNuc;

    @Column(name = "source", length = 16, nullable = false)
    private String source = "quarterly";

    public Integer getId() {
        return id;
    }

    /** Which table the rate is in: {@code roe} or {@code bsr}. */
    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getEffective() {
        return effective;
    }

    public void setEffective(LocalDate effective) {
        this.effective = effective;
    }

    /** How many units of the currency one neutral unit buys, exactly as filed. */
    public BigDecimal getUnitsPerNuc() {
        return unitsPerNuc;
    }

    public void setUnitsPerNuc(BigDecimal unitsPerNuc) {
        this.unitsPerNuc = unitsPerNuc;
    }

    /** Where the row came from: {@code quarterly} unless somebody says otherwise. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** {@code <Rate roe EUR from 2025-01-01>}. */
    @Override
    public String toString() {
        return "<Rate " + tableName + " " + currency + " from " + effective + ">";
    }
}
