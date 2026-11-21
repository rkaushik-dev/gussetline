package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** One collection run and what each of its tasks did, kept for the reliability report. */
@Entity
@Table(name = "runs", indexes = @Index(name = "ix_runs_started_at", columnList = "started_at"))
public class RunRow extends Timestamped {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "tasks", nullable = false)
    private int tasks;

    @Column(name = "calls", nullable = false)
    private int calls;

    @Column(name = "offers", nullable = false)
    private int offers;

    @Column(name = "skipped_breaker", nullable = false)
    private int skippedBreaker;

    @Column(name = "skipped_budget", nullable = false)
    private int skippedBudget;

    @Convert(converter = Amount.class)
    @Column(name = "cost_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal costAmount = BigDecimal.ZERO;

    @Column(name = "cost_currency", length = 3, nullable = false)
    private String costCurrency = "EUR";

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<RunResultRow> results = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    /** When the run began, in UTC. */
    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    /** When the run said it was done, in UTC, if it ever did. */
    public Optional<LocalDateTime> getFinishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    /** Sets when the run was done; null while it is not. */
    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getTasks() {
        return tasks;
    }

    public void setTasks(int tasks) {
        this.tasks = tasks;
    }

    public int getCalls() {
        return calls;
    }

    public void setCalls(int calls) {
        this.calls = calls;
    }

    public int getOffers() {
        return offers;
    }

    public void setOffers(int offers) {
        this.offers = offers;
    }

    public int getSkippedBreaker() {
        return skippedBreaker;
    }

    public void setSkippedBreaker(int skippedBreaker) {
        this.skippedBreaker = skippedBreaker;
    }

    public int getSkippedBudget() {
        return skippedBudget;
    }

    public void setSkippedBudget(int skippedBudget) {
        this.skippedBudget = skippedBudget;
    }

    /** What the run cost the suppliers' meters: nothing unless somebody says. */
    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public void setCostAmount(BigDecimal costAmount) {
        this.costAmount = costAmount;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public void setCostCurrency(String costCurrency) {
        this.costCurrency = costCurrency;
    }

    /** What each task did; read only. */
    public List<RunResultRow> getResults() {
        return Collections.unmodifiableList(results);
    }

    public void addResult(RunResultRow result) {
        result.setRun(this);
        results.add(result);
    }

    /** Replace every result, deleting the ones not given again. */
    public void setResults(List<RunResultRow> replacement) {
        List<RunResultRow> kept = List.copyOf(replacement);
        results.clear();
        kept.forEach(this::addResult);
    }

    /** {@code <Run 2025-06-08 03:00 2 tasks>}. */
    @Override
    public String toString() {
        return "<Run " + MINUTE.format(startedAt) + " " + tasks + " tasks>";
    }
}
