package finance.trove.taxexport;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The tax-form line one category belongs on, in one jurisdiction
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §3 V42-row (plan numbering; shipped as
 * V36__tax_line_mapping.sql), §6).
 *
 * <p><strong>Two tiers in one table.</strong> {@link #getUserId()} {@code == null} is the
 * install-wide SEED row that V36 writes for all 51 system categories in both jurisdictions; a
 * non-null user id is that member's own override. {@link TaxLineMappingService#resolve} reads both
 * in one query and lets the override win, so a household mate's override is invisible to everybody
 * else — a filing is individual.
 *
 * <p>{@code UNIQUE (jurisdiction, category_id, user_id) NULLS NOT DISTINCT} is what makes the
 * seed row unique against itself and therefore targetable by the upsert behind
 * {@code PUT /v1/tax-export/lines/{categoryId}}; see V36's header for why the plain-UNIQUE form
 * would not.
 *
 * <p><strong>Labels only.</strong> {@link #getLineCode()} and {@link #getLineLabel()} are strings
 * a human reads off a form and {@link #isDeductible()} is an assertion the seed makes or the
 * member overrides. Nothing here is a rate, a bracket or a computed figure (owner decision 2,
 * §9 risk 15), and the server never interprets either string.
 */
@Entity
@Table(name = "tax_line_mapping")
public class TaxLineMapping {

    /**
     * The seeded {@code line_code} meaning "this category has no line on either form".
     *
     * <p>Deliberately different from having no row at all: a {@code NONE} row is a statement the
     * export prints, and a missing row is an empty cell. Personal categories are seeded this way
     * rather than onto a business line such as T2125 9270, because a wrong line code on a tax
     * form is worse than a blank one.
     */
    public static final String NO_LINE_CODE = "NONE";

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", nullable = false, length = 2, updatable = false)
    private Jurisdiction jurisdiction;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "line_code", nullable = false, length = 20)
    private String lineCode;

    @Column(name = "line_label", nullable = false, length = 200)
    private String lineLabel;

    @Column(name = "deductible", nullable = false)
    private boolean deductible;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Maintained in SQL, not by a JPA callback: V36's column default on insert and
     * {@code updated_at = now()} in the {@code ON CONFLICT DO UPDATE} clause. No code path here
     * ever dirties a managed row, so there is no {@code @PreUpdate} to keep honest.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected TaxLineMapping() {
        // JPA
    }

    /**
     * Materialises one row, key columns and all.
     *
     * <p>Nothing in production calls it: the seed rows are written by V36 and a member's override
     * by {@link TaxLineMappingRepository#upsertOverride}'s single {@code INSERT … ON CONFLICT}
     * statement, and every row this class returns at runtime is hydrated by Hibernate through the
     * no-arg constructor above. It is package-private, and the tests in this package use it to
     * stand in for rows those two writers produce.
     */
    TaxLineMapping(Jurisdiction jurisdiction, UUID categoryId, UUID userId, String lineCode,
                   String lineLabel, boolean deductible) {
        this.jurisdiction = jurisdiction;
        this.categoryId = categoryId;
        this.userId = userId;
        this.lineCode = lineCode;
        this.lineLabel = lineLabel;
        this.deductible = deductible;
        this.createdAt = Instants.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Jurisdiction getJurisdiction() {
        return jurisdiction;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    /** {@code null} on the install-wide seed row; a member's id on an override. */
    public UUID getUserId() {
        return userId;
    }

    public String getLineCode() {
        return lineCode;
    }

    public String getLineLabel() {
        return lineLabel;
    }

    public boolean isDeductible() {
        return deductible;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** This row as the resolver's answer. */
    TaxLine toTaxLine() {
        return new TaxLine(lineCode, lineLabel, deductible, userId != null);
    }
}
