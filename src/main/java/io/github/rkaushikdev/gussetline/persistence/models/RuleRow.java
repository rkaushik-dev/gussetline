package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** A rule and its provisions, kept as the filed text plus what was read out of it. */
@Entity
@Table(
        name = "rules",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_rules_carrier_number_tariff_number",
                        columnNames = {"carrier", "number", "tariff_number"}),
        indexes = {
            @Index(name = "ix_rules_load_id", columnList = "load_id"),
            @Index(name = "ix_rules_carrier", columnList = "carrier"),
            @Index(name = "ix_rules_number", columnList = "number")
        })
public class RuleRow extends Timestamped {

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
            foreignKey = @ForeignKey(name = "fk_rules_load_id_tariff_loads"))
    private LoadRow load;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "number", length = 8, nullable = false)
    private String number;

    @Column(name = "tariff_number", length = 4, nullable = false)
    private String tariffNumber = "1";

    @Column(name = "filed_text", length = 8192, nullable = false)
    private String filedText = "";

    /** In the order they were filed, which is the order of their ids. */
    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ProvisionRow> provisions = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    /** The load the rule came in on, if it came in on one. */
    public Optional<Integer> getLoadId() {
        return Optional.ofNullable(loadId);
    }

    /** Sets the load the rule came in on; null for none. */
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

    public String getTariffNumber() {
        return tariffNumber;
    }

    public void setTariffNumber(String tariffNumber) {
        this.tariffNumber = tariffNumber;
    }

    public String getFiledText() {
        return filedText;
    }

    public void setFiledText(String filedText) {
        this.filedText = filedText;
    }

    /** The provisions in the order they were filed, read only; change them through the methods below. */
    public List<ProvisionRow> getProvisions() {
        return Collections.unmodifiableList(provisions);
    }

    /** One more provision under this rule. */
    public void addProvision(ProvisionRow provision) {
        provision.setRule(this);
        provisions.add(provision);
    }

    /** Take a provision out of this rule, which deletes it. */
    public void removeProvision(ProvisionRow provision) {
        provisions.remove(provision);
    }

    /** Replace every provision, deleting the ones not given again. */
    public void setProvisions(List<ProvisionRow> replacement) {
        List<ProvisionRow> kept = List.copyOf(replacement);
        provisions.clear();
        kept.forEach(this::addProvision);
    }

    /** {@code <Rule LH 1100>}. */
    @Override
    public String toString() {
        return "<Rule " + carrier + " " + number + ">";
    }
}
