package finance.trove.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PUT /v1/me/preferences} — a partial patch, not a replacement.
 *
 * <p>An absent key means "leave it alone". The onboarding flow sets timezone and locale on one
 * screen and {@code onboardingCompleted} on another, and a PUT that treated absence as "clear it"
 * would blank the first screen's answers when the second one saved.
 *
 * <p><strong>Why this is a class and not a record.</strong> {@code region} is typed
 * {@code string | null} in both clients ({@code apps/web/src/api/types.ts:176-181},
 * {@code apps/mobile/src/api/types.ts:290-299}), and both send patches as
 * {@code Partial<Preferences>} — so {@code {"region": null}} is the shape a client produces when
 * the user picks "no province". On a record, {@code null} and absent are the same value and the
 * clear was impossible: the field could only be blanked by sending the empty string, which no
 * client does. Jackson calls a setter for an explicit {@code null} and never calls it for an
 * absent key, so a setter that records having been called is what tells the two apart. The
 * empty-string path is kept as well, for compatibility with anything already sending it.
 *
 * <p>The accessors are deliberately not named {@code getX}: Jackson would then see each property
 * as readable too, and this object is only ever deserialised. Validation still runs — Hibernate
 * Validator reads the annotated fields directly.
 *
 * <p>{@code currency} and {@code country} are deliberately not here even though
 * {@link PreferencesResponse} returns them: they are identity-level and fixed at registration
 * (§4.2). Returning a field that cannot be written is the lesser evil against a client having to
 * read two endpoints to render one settings form.
 */
public class PreferencesUpdateRequest {

    @Size(max = 64)
    private String timezone;

    @Size(max = 10)
    private String locale;

    private Boolean onboardingCompleted;

    @Size(max = 3)
    private String region;

    private boolean timezonePresent;
    private boolean localePresent;
    private boolean onboardingCompletedPresent;
    private boolean regionPresent;

    /** An empty patch: every key absent, so applying it changes nothing. */
    public static PreferencesUpdateRequest patch() {
        return new PreferencesUpdateRequest();
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
        this.timezonePresent = true;
    }

    public void setLocale(String locale) {
        this.locale = locale;
        this.localePresent = true;
    }

    public void setOnboardingCompleted(Boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
        this.onboardingCompletedPresent = true;
    }

    public void setRegion(String region) {
        this.region = region;
        this.regionPresent = true;
    }

    /** Test seam: the same effect as the key being present in the JSON with this value. */
    public PreferencesUpdateRequest withTimezone(String value) {
        setTimezone(value);
        return this;
    }

    /** Test seam: the same effect as the key being present in the JSON with this value. */
    public PreferencesUpdateRequest withLocale(String value) {
        setLocale(value);
        return this;
    }

    /** Test seam: the same effect as the key being present in the JSON with this value. */
    public PreferencesUpdateRequest withOnboardingCompleted(Boolean value) {
        setOnboardingCompleted(value);
        return this;
    }

    /** Test seam: {@code withRegion(null)} is {@code {"region": null}}, which clears it. */
    public PreferencesUpdateRequest withRegion(String value) {
        setRegion(value);
        return this;
    }

    public String timezone() {
        return timezone;
    }

    public String locale() {
        return locale;
    }

    public Boolean onboardingCompleted() {
        return onboardingCompleted;
    }

    public String region() {
        return region;
    }

    public boolean timezonePresent() {
        return timezonePresent;
    }

    public boolean localePresent() {
        return localePresent;
    }

    public boolean onboardingCompletedPresent() {
        return onboardingCompletedPresent;
    }

    /** Whether the caller sent a {@code region} key at all — {@code null} counts as sending one. */
    public boolean regionPresent() {
        return regionPresent;
    }
}
