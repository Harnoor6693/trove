package finance.trove.households;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The scoping root of the whole schema (owner decision 6: one shared household ledger per
 * install; {@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5).
 *
 * <p>Every ledger-owned table carries {@code household_id} and every repository over one filters
 * by it. Members of a household see each other's accounts, transactions, budgets and receipts in
 * full; nothing crosses a household boundary, admin included.
 *
 * <p>{@code registrationOpen} lives here rather than in a settings table because under
 * one-household-per-install the household <em>is</em> the install. §4.2's
 * {@code PUT /v1/admin/settings {registrationOpen}} is the write path; it is false by default,
 * because an open {@code /register} on the public internet lets a stranger create an account on
 * somebody's finance server (gap P2-7).
 *
 * <p>{@code scheduledSyncEnabled} (V45, C-01r) lives here for the same reason and is false by
 * default for the same kind of reason: {@code ScheduledSyncJob} calls the SimpleFIN bridge on the
 * server's own schedule only once an admin has said so.
 */
@Entity
@Table(name = "households")
public class Household {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "registration_open", nullable = false)
    private boolean registrationOpen;

    @Column(name = "scheduled_sync_enabled", nullable = false)
    private boolean scheduledSyncEnabled;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected Household() {
        // JPA
    }

    public Household(String name, boolean registrationOpen) {
        this.name = name;
        this.registrationOpen = registrationOpen;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isRegistrationOpen() {
        return registrationOpen;
    }

    public void setRegistrationOpen(boolean registrationOpen) {
        this.registrationOpen = registrationOpen;
    }

    public boolean isScheduledSyncEnabled() {
        return scheduledSyncEnabled;
    }

    public void setScheduledSyncEnabled(boolean scheduledSyncEnabled) {
        this.scheduledSyncEnabled = scheduledSyncEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
