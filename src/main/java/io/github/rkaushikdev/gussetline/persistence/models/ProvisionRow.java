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
import java.util.Optional;

/**
 * One category inside a rule, stored as the fields read out of it.
 *
 * <p>The values are a small set of named columns rather than a blob, because the reports ask
 * questions like which fares want a Saturday night, and a blob answers that only by parsing every
 * row.
 *
 * <p>Two categories do not fit six columns, and rather than widen the table for every category to
 * suit one of them, they spill into {@code detail}. That is a blob by another name and it is
 * deliberately the exception: a field goes there only when no report will ever filter on it, and
 * every kind that uses it has to survive the round trip test that runs over all of them.
 */
@Entity
@Table(
        name = "provisions",
        indexes = {
            @Index(name = "ix_provisions_rule_id", columnList = "rule_id"),
            @Index(name = "ix_provisions_category", columnList = "rule_id, category")
        })
public class ProvisionRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "rule_id", nullable = false)
    private Integer ruleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "rule_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_provisions_rule_id_rules"))
    private RuleRow rule;

    @Column(name = "category", nullable = false)
    private int category;

    @Column(name = "kind", length = 32, nullable = false)
    private String kind;

    @Column(name = "number_one")
    private Integer numberOne;

    @Column(name = "number_two")
    private Integer numberTwo;

    @Column(name = "text_one", length = 64, nullable = false)
    private String textOne = "";

    @Column(name = "text_two", length = 64, nullable = false)
    private String textTwo = "";

    @Convert(converter = Amount.class)
    @Column(name = "amount", length = Amount.LENGTH)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "";

    @Column(name = "flag_one", nullable = false)
    private boolean flagOne;

    @Column(name = "flag_two", nullable = false)
    private boolean flagTwo;

    @Column(name = "detail", length = 512, nullable = false)
    private String detail = "";

    public Integer getId() {
        return id;
    }

    /** The rule the provision belongs to; set from the rule itself when added through it. */
    public Integer getRuleId() {
        return ruleId;
    }

    public void setRuleId(Integer ruleId) {
        this.ruleId = ruleId;
    }

    /** The rule the provision belongs to, once added through one or read back. */
    public RuleRow getRule() {
        return rule;
    }

    void setRule(RuleRow rule) {
        this.rule = rule;
        this.ruleId = rule.getId();
    }

    @PrePersist
    void followRule() {
        if (rule != null && rule.getId() != null) {
            ruleId = rule.getId();
        }
    }

    /** The category's number. */
    public int getCategory() {
        return category;
    }

    public void setCategory(int category) {
        this.category = category;
    }

    /** The name of the codec that wrote the row, which is how it is read back. */
    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Optional<Integer> getNumberOne() {
        return Optional.ofNullable(numberOne);
    }

    /** Sets the first number; null for none. */
    public void setNumberOne(Integer numberOne) {
        this.numberOne = numberOne;
    }

    public Optional<Integer> getNumberTwo() {
        return Optional.ofNullable(numberTwo);
    }

    /** Sets the second number; null for none. */
    public void setNumberTwo(Integer numberTwo) {
        this.numberTwo = numberTwo;
    }

    public String getTextOne() {
        return textOne;
    }

    public void setTextOne(String textOne) {
        this.textOne = textOne;
    }

    public String getTextTwo() {
        return textTwo;
    }

    public void setTextTwo(String textTwo) {
        this.textTwo = textTwo;
    }

    public Optional<BigDecimal> getAmount() {
        return Optional.ofNullable(amount);
    }

    /** Sets the amount; null for none. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** The amount's currency, or empty text when the provision carries no money. */
    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isFlagOne() {
        return flagOne;
    }

    public void setFlagOne(boolean flagOne) {
        this.flagOne = flagOne;
    }

    public boolean isFlagTwo() {
        return flagTwo;
    }

    public void setFlagTwo(boolean flagTwo) {
        this.flagTwo = flagTwo;
    }

    /** The spilled fields, as {@code key=value} pairs joined by {@code ;}, or empty text. */
    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    /** {@code <Provision cat 6 minimum_stay>}. */
    @Override
    public String toString() {
        return "<Provision cat " + category + " " + kind + ">";
    }
}
