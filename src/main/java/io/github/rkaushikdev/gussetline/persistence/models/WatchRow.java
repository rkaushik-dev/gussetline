package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Amount;
import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** A standing instruction to keep looking at one ticket's market, and what it has raised. */
@Entity
@Table(
        name = "watches",
        indexes = {
            @Index(name = "ix_watches_ticket_id", columnList = "ticket_id"),
            @Index(name = "ix_watches_series_key", columnList = "series_key"),
            @Index(name = "ix_watches_active", columnList = "active"),
            @Index(name = "ix_watches_live", columnList = "active, departure")
        })
public class WatchRow extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "ticket_id", nullable = false)
    private Integer ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_watches_ticket_id_tickets"))
    private TicketRow ticket;

    @Column(name = "series_key", length = 48, nullable = false)
    private String seriesKey;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "cabin", length = 1, nullable = false)
    private String cabin = "Y";

    @Convert(converter = Amount.class)
    @Column(name = "threshold_amount", length = Amount.LENGTH, nullable = false)
    private BigDecimal thresholdAmount;

    @Column(name = "threshold_currency", length = 3, nullable = false)
    private String thresholdCurrency;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "retired_reason", length = 64, nullable = false)
    private String retiredReason = "";

    @Column(name = "last_checked")
    private LocalDateTime lastChecked;

    @Column(name = "checks", nullable = false)
    private int checks;

    @OneToMany(mappedBy = "watch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecommendationRow> recommendations = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    /** The ticket watched; set from it when the watch is added through it. */
    public Integer getTicketId() {
        return ticketId;
    }

    public void setTicketId(Integer ticketId) {
        this.ticketId = ticketId;
    }

    /** The ticket watched, once the watch is added through it or read back. */
    public TicketRow getTicket() {
        return ticket;
    }

    void setTicket(TicketRow ticket) {
        this.ticket = ticket;
        this.ticketId = ticket.getId();
    }

    @PrePersist
    void followTicket() {
        if (ticket != null && ticket.getId() != null) {
            ticketId = ticket.getId();
        }
    }

    /** The series the watch reads its history from. */
    public String getSeriesKey() {
        return seriesKey;
    }

    public void setSeriesKey(String seriesKey) {
        this.seriesKey = seriesKey;
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

    public LocalDate getDeparture() {
        return departure;
    }

    public void setDeparture(LocalDate departure) {
        this.departure = departure;
    }

    public String getCabin() {
        return cabin;
    }

    public void setCabin(String cabin) {
        this.cabin = cabin;
    }

    /** What a drop has to beat before it is worth raising. */
    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public void setThresholdAmount(BigDecimal thresholdAmount) {
        this.thresholdAmount = thresholdAmount;
    }

    public String getThresholdCurrency() {
        return thresholdCurrency;
    }

    public void setThresholdCurrency(String thresholdCurrency) {
        this.thresholdCurrency = thresholdCurrency;
    }

    /** Whether the watch is still live, which a new one is. */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Why the watch was retired, or empty text while it is live. */
    public String getRetiredReason() {
        return retiredReason;
    }

    public void setRetiredReason(String retiredReason) {
        this.retiredReason = retiredReason;
    }

    /** When the night run last looked, in UTC, if it has. */
    public Optional<LocalDateTime> getLastChecked() {
        return Optional.ofNullable(lastChecked);
    }

    /** Sets when the night run last looked; null for never. */
    public void setLastChecked(LocalDateTime lastChecked) {
        this.lastChecked = lastChecked;
    }

    public int getChecks() {
        return checks;
    }

    public void setChecks(int checks) {
        this.checks = checks;
    }

    /** What the watch has raised; read only. */
    public List<RecommendationRow> getRecommendations() {
        return Collections.unmodifiableList(recommendations);
    }

    public void addRecommendation(RecommendationRow recommendation) {
        recommendation.setWatch(this);
        recommendations.add(recommendation);
    }

    /** Take a recommendation off the watch, which deletes it. */
    public void removeRecommendation(RecommendationRow recommendation) {
        recommendations.remove(recommendation);
    }

    /** {@code <Watch FRA-NYC-2025-07-04-Y live>}, or {@code retired, <why>} in place of {@code live}. */
    @Override
    public String toString() {
        String state = active ? "live" : "retired, " + retiredReason;
        return "<Watch " + seriesKey + " " + state + ">";
    }
}
