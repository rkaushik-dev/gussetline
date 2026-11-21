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

/** One flight coupon, which is what actually gets flown or reissued. */
@Entity
@Table(
        name = "coupons",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_coupons_ticket_id_position",
                        columnNames = {"ticket_id", "position"}),
        indexes = @Index(name = "ix_coupons_ticket_id", columnList = "ticket_id"))
public class CouponRow {

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
            foreignKey = @ForeignKey(name = "fk_coupons_ticket_id_tickets"))
    private TicketRow ticket;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "carrier", length = 2, nullable = false)
    private String carrier;

    @Column(name = "flight_number", length = 5, nullable = false)
    private String flightNumber;

    @Column(name = "origin", length = 3, nullable = false)
    private String origin;

    @Column(name = "destination", length = 3, nullable = false)
    private String destination;

    @Column(name = "departure", nullable = false)
    private LocalDateTime departure;

    @Column(name = "booking_class", length = 1, nullable = false)
    private String bookingClass;

    @Column(name = "fare_basis", length = 16, nullable = false)
    private String fareBasis;

    @Column(name = "status", length = 8, nullable = false)
    private String status = "OK";

    @Column(name = "flown", nullable = false)
    private boolean flown;

    public Integer getId() {
        return id;
    }

    /** The ticket the coupon belongs to; set from it when the coupon is added through it. */
    public Integer getTicketId() {
        return ticketId;
    }

    public void setTicketId(Integer ticketId) {
        this.ticketId = ticketId;
    }

    /** The ticket the coupon belongs to, once added through one or read back. */
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

    /** Where the coupon falls on the ticket, nought first. */
    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
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

    public String getBookingClass() {
        return bookingClass;
    }

    public void setBookingClass(String bookingClass) {
        this.bookingClass = bookingClass;
    }

    public String getFareBasis() {
        return fareBasis;
    }

    public void setFareBasis(String fareBasis) {
        this.fareBasis = fareBasis;
    }

    /** The coupon's status code: {@code OK} until it is flown or exchanged. */
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isFlown() {
        return flown;
    }

    public void setFlown(boolean flown) {
        this.flown = flown;
    }

    /** {@code <Coupon 0 LH400 OK>}. */
    @Override
    public String toString() {
        return "<Coupon " + position + " " + carrier + flightNumber + " " + status + ">";
    }
}
