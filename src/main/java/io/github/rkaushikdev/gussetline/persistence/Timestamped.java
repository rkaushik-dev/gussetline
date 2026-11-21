package io.github.rkaushikdev.gussetline.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * When a row was written and last changed, both as UTC {@link Stamp}s.
 *
 * <p>A row written without either stamp is given the time it is written in both. A row that is
 * changed is given the time of the change in {@code updated_at}, unless the change set that column
 * itself, in which case the value given stands.
 */
@MappedSuperclass
public abstract class Timestamped {

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** The {@code updated_at} the database holds, to tell a change that set it from one that did not. */
    @Transient
    private LocalDateTime stored;

    /** When the row was first written, in UTC; nothing until it has been. */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime when) {
        createdAt = when;
    }

    /** Sets the creation stamp from a time with an offset, converted to UTC. */
    public void setCreatedAt(OffsetDateTime when) {
        createdAt = Stamp.utc(when);
    }

    /** When the row was last changed, in UTC; nothing until it has been written. */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime when) {
        updatedAt = when;
    }

    /** Sets the change stamp from a time with an offset, converted to UTC. */
    public void setUpdatedAt(OffsetDateTime when) {
        updatedAt = Stamp.utc(when);
    }

    @PrePersist
    void stampWritten() {
        LocalDateTime now = Stamp.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void stampChanged() {
        if (Objects.equals(updatedAt, stored)) {
            updatedAt = Stamp.now();
        }
    }

    @PostLoad
    @PostPersist
    @PostUpdate
    void rememberStored() {
        stored = updatedAt;
    }
}
