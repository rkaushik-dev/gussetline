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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** One flown sector of an observed itinerary, at its position in the journey. */
@Entity
@Table(
        name = "observation_sectors",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_observation_sectors_observation_id_position",
                        columnNames = {"observation_id", "position"}),
        indexes = @Index(name = "ix_observation_sectors_observation_id", columnList = "observation_id"))
public class SectorRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "observation_id", nullable = false)
    private Integer observationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "observation_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_observation_sectors_observation_id_observations"))
    private ObservationRow observation;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "marketing_carrier", length = 2, nullable = false)
    private String marketingCarrier;

    @Column(name = "operating_carrier", length = 2, nullable = false)
    private String operatingCarrier = "";

    @Column(name = "flight_number", length = 5, nullable = false)
    private String flightNumber;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDateTime departure;

    @Column(name = "arrival", nullable = false)
    private LocalDateTime arrival;

    @Column(name = "day_change", nullable = false)
    private int dayChange;

    @Column(name = "booking_class", length = 1, nullable = false)
    private String bookingClass;

    @Column(name = "cabin", length = 1, nullable = false)
    private String cabin = "Y";

    public Integer getId() {
        return id;
    }

    /** The observation the sector belongs to; set from it when the sector is added through it. */
    public Integer getObservationId() {
        return observationId;
    }

    public void setObservationId(Integer observationId) {
        this.observationId = observationId;
    }

    /** The observation the sector belongs to, once added through one or read back. */
    public ObservationRow getObservation() {
        return observation;
    }

    void setObservation(ObservationRow observation) {
        this.observation = observation;
        this.observationId = observation.getId();
    }

    @PrePersist
    void followObservation() {
        if (observation != null && observation.getId() != null) {
            observationId = observation.getId();
        }
    }

    /** Where the sector falls in the journey, nought first. */
    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getMarketingCarrier() {
        return marketingCarrier;
    }

    public void setMarketingCarrier(String marketingCarrier) {
        this.marketingCarrier = marketingCarrier;
    }

    /** Who flies it, or empty text when the marketing carrier does. */
    public String getOperatingCarrier() {
        return operatingCarrier;
    }

    public void setOperatingCarrier(String operatingCarrier) {
        this.operatingCarrier = operatingCarrier;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
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

    /** The local wall clock at the origin. */
    public LocalDateTime getDeparture() {
        return departure;
    }

    public void setDeparture(LocalDateTime departure) {
        this.departure = departure;
    }

    /** The local wall clock at the destination. */
    public LocalDateTime getArrival() {
        return arrival;
    }

    public void setArrival(LocalDateTime arrival) {
        this.arrival = arrival;
    }

    public int getDayChange() {
        return dayChange;
    }

    public void setDayChange(int dayChange) {
        this.dayChange = dayChange;
    }

    public String getBookingClass() {
        return bookingClass;
    }

    public void setBookingClass(String bookingClass) {
        this.bookingClass = bookingClass;
    }

    public String getCabin() {
        return cabin;
    }

    public void setCabin(String cabin) {
        this.cabin = cabin;
    }

    /** {@code <Sector LH400 FRAJFK>}. */
    @Override
    public String toString() {
        return "<Sector " + marketingCarrier + flightNumber + " " + origin + destination + ">";
    }
}
