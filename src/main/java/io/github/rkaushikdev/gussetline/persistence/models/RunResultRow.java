package io.github.rkaushikdev.gussetline.persistence.models;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * What one task in a run did, including the ones that did nothing.
 *
 * <p>Two of the readings the reliability report cannot get anywhere else. A payload the archive
 * already held says the supplier is serving a cache back ({@code fresh} is false), and offers inside
 * a payload that would not parse say the feed has moved under the parser ({@code problems}). Neither
 * shows in a status code.
 */
@Entity
@Table(
        name = "run_results",
        indexes = {
            @Index(name = "ix_run_results_run_id", columnList = "run_id"),
            @Index(name = "ix_run_results_supplier", columnList = "supplier"),
            @Index(name = "ix_run_results_outcome", columnList = "run_id, outcome")
        })
public class RunResultRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "run_id", nullable = false)
    private Integer runId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "run_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_run_results_run_id_runs"))
    private RunRow run;

    @Column(name = "supplier", length = 16, nullable = false)
    private String supplier;

    @Column(name = "market", length = 8, nullable = false)
    private String market;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome;

    @Column(name = "offers", nullable = false)
    private int offers;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "digest", length = 32, nullable = false)
    private String digest = "";

    @Column(name = "reason", length = 128, nullable = false)
    private String reason = "";

    @Column(name = "fresh", nullable = false)
    private boolean fresh = true;

    @Column(name = "problems", nullable = false)
    private int problems;

    public Integer getId() {
        return id;
    }

    /** The run the result belongs to; set from the run when added through it. */
    public Integer getRunId() {
        return runId;
    }

    public void setRunId(Integer runId) {
        this.runId = runId;
    }

    /** The run the result belongs to, once added through it or read back. */
    public RunRow getRun() {
        return run;
    }

    void setRun(RunRow run) {
        this.run = run;
        this.runId = run.getId();
    }

    @PrePersist
    void followRun() {
        if (run != null && run.getId() != null) {
            runId = run.getId();
        }
    }

    public String getSupplier() {
        return supplier;
    }

    public void setSupplier(String supplier) {
        this.supplier = supplier;
    }

    /** The market's key: {@code FRA-NYC}. */
    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public LocalDate getDeparture() {
        return departure;
    }

    public void setDeparture(LocalDate departure) {
        this.departure = departure;
    }

    /** The outcome's value: {@code ok}, {@code budget} and so on. */
    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public int getOffers() {
        return offers;
    }

    public void setOffers(int offers) {
        this.offers = offers;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /** Whether the payload was new to the archive, which is the reading for every row older than it. */
    public boolean isFresh() {
        return fresh;
    }

    public void setFresh(boolean fresh) {
        this.fresh = fresh;
    }

    public int getProblems() {
        return problems;
    }

    public void setProblems(int problems) {
        this.problems = problems;
    }

    /** {@code <Result gds FRA-NYC ok>}. */
    @Override
    public String toString() {
        return "<Result " + supplier + " " + market + " " + outcome + ">";
    }
}
