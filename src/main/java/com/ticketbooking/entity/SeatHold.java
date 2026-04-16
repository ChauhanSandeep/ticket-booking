package com.ticketbooking.entity;

import com.ticketbooking.entity.enums.HoldStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "seat_holds", uniqueConstraints = {
        @UniqueConstraint(name = "uq_active_hold_per_user_event",
                          columnNames = {"event_id", "active_hold_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_id", nullable = false, unique = true, updatable = false)
    private UUID holdId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private HoldStatus status = HoldStatus.ACTIVE;

    // Equals userId while status=ACTIVE, NULL otherwise. Combined with a
    // UNIQUE (event_id, active_hold_key) constraint, NULL-distinctness gives
    // us "at most one ACTIVE hold per user per event" without a partial index.
    @Column(name = "active_hold_key")
    private String activeHoldKey;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        syncActiveHoldKey();
    }

    @PreUpdate
    protected void onUpdate() {
        syncActiveHoldKey();
    }

    private void syncActiveHoldKey() {
        // Ensure that the active hold key is always the user id while the hold is active
        this.activeHoldKey = (status == HoldStatus.ACTIVE) ? userId : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeatHold seatHold)) return false;
        return holdId != null && holdId.equals(seatHold.holdId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(holdId);
    }
}
