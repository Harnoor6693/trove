package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.insight.CoachInsightRepository;
import finance.trove.ai.insight.InsightRecipients;
import finance.trove.ai.insight.InsightService;
import finance.trove.ai.insight.WeeklyInsightJob;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.time.UserClock;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The weekly insight, end to end: whose week, whose currency, whose jurisdiction, and exactly one
 * row per member per week ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6, §6;
 * {@code docs/recovery/07-READINESS-GAPS.md} MUST row 15; inventory model-manager D5/D6).
 *
 * <p>Four things the source got wrong and this class holds:
 * <ul>
 *   <li>the recipients are members whose <strong>own</strong> {@code ai_settings.enabled} is true,
 *       not "members who have chatted" (D6);</li>
 *   <li>the jurisdiction is {@code users.country}, not the literal {@code "CA"} its own unit test
 *       asserted as correct (D5);</li>
 *   <li>the currency is {@code users.currency}, not a hard-coded fallback (F1);</li>
 *   <li>the week is the RECIPIENT's, so two members in different zones get different boundaries
 *       from one UTC run (gap row 15).</li>
 * </ul>
 *
 * <p>The gateway is stubbed for the reason {@link CoachStubGateway} explains; the repository, the
 * encryption, the recipient enumeration, the Postgres unique index and the job's own loop are all
 * the real ones.
 */
class WeeklyInsightIT extends AuthFlowSupport {

    /** A Monday, 06:00 UTC: Toronto is already Monday, Vancouver is still Sunday. */
    private static final Instant RUN_AT = Instant.parse("2026-09-07T06:00:00Z");

    private static final LocalDate TORONTO_WEEK = LocalDate.of(2026, 8, 31);
    private static final LocalDate VANCOUVER_WEEK = LocalDate.of(2026, 8, 24);

    private static final String NOTE = "You spent 42.00 less than the week before, mostly on fuel.";

    /**
     * A timezone no other member in the shared suite database uses. The weekly job visits every
     * member on the install whose AI is on, so a test that wants ITS OWN prompt out of the run has
     * to be able to name it — and never by an absolute position or an absolute count.
     */
    private static final String UNIQUE_ZONE = "America/St_Johns";

    @Autowired
    private CoachContextBuilder contextBuilder;

    @Autowired
    private CoachPromptsProperties prompts;

    @Autowired
    private CoachProperties coachProperties;

    @Autowired
    private CoachInsightRepository insights;

    @Autowired
    private InsightRecipients recipients;

    @Autowired
    private FieldEncryptionService encryption;

    @Autowired
    private UserClock clock;

    private AiStubServer stub;
    private CoachStubGateway gateway;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        gateway = new CoachStubGateway(stub);
        stub.json("/chat/completions",
            "{\"model\":\"" + CoachStubGateway.MODEL
                + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + NOTE + "\"}}]}");
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("a recipient gets one note for their own week, and a second run adds none")
    void oneNotePerMemberPerWeekHoweverOftenTheJobRuns() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "insight-run");
        makeRecipient(member, "America/Toronto", "USD", "US");

        WeeklyInsightJob job = job();
        WeeklyInsightJob.Result first = job.runOnce(RUN_AT);
        assertThat(first.considered())
            .as("the enumeration found somebody — an empty run proves nothing")
            .isPositive();

        List<Map<String, Object>> rows = notesFor(member.userId());
        assertThat(rows)
            .as("exactly one note, for the member's own week")
            .hasSize(1);
        assertThat(((java.sql.Date) rows.get(0).get("week_start")).toLocalDate())
            .as("the Monday of the week that just ended, in America/Toronto")
            .isEqualTo(TORONTO_WEEK);
        assertThat(encryption.decrypt((String) rows.get(0).get("content_encrypted")))
            .isEqualTo(NOTE);
        assertThat(rows.get(0).get("delivered"))
            .as("nothing has delivered it anywhere yet; reading GET /v1/insights will not "
                + "change that either")
            .isEqualTo(false);

        WeeklyInsightJob.Result second = job.runOnce(RUN_AT);
        assertThat(notesFor(member.userId()))
            .as("ON CONFLICT (user_id, week_start) DO NOTHING: a second run — which is exactly "
                + "what a ShedLock lockAtMostFor expiry produces — costs nothing")
            .hasSize(1);
        assertThat(second.written() + second.skipped())
            .as("the second pass visited the same people and wrote nobody a duplicate")
            .isEqualTo(second.considered());
    }

    @Test
    @DisplayName("the note is written for the member's own currency and jurisdiction, never CA/CAD")
    void theNoteIsInTheMembersOwnCurrencyAndJurisdiction() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "insight-jurisdiction");
        // A zone no other member in the shared suite database uses, so the prompt this test looks
        // for can be picked out of a run that visits every enabled member on the install. Its week
        // boundary is the same as Toronto's at this instant (UTC-2:30 on a Monday morning).
        makeRecipient(member, UNIQUE_ZONE, "USD", "US");

        job().runOnce(RUN_AT);

        String instruction = instructionMentioning(UNIQUE_ZONE);
        assertThat(instruction)
            .as("the job asked for a note about this member's own week")
            .isNotNull();
        assertThat(instruction)
            .as("their own currency and their own jurisdiction — the source hard-coded both "
                + "(inventory D5's \"CA\" for every user on earth, finding F1's \"CAD\")")
            .contains("in USD and they file in US.")
            .doesNotContain("CAD");
        assertThat(instruction)
            .as("and it is that member's own week, in that member's own zone")
            .contains(TORONTO_WEEK.toString())
            .contains(TORONTO_WEEK.plusDays(6).toString());
        assertThat(gateway.sensitivities())
            .as("a weekly note is written from the household's ledger, like every coach payload")
            .containsOnly(AiSensitivity.SENSITIVE);
    }

    @Test
    @DisplayName("two members, two zones, one UTC run — two different week boundaries")
    void theWeekBelongsToTheRecipient() {
        Session east = inviteAndRegister(loginBootstrapAdmin(), "insight-east");
        Session west = inviteAndRegister(loginBootstrapAdmin(), "insight-west");
        makeRecipient(east, "America/Toronto", "CAD", "CA");
        makeRecipient(west, "America/Vancouver", "CAD", "CA");

        job().runOnce(RUN_AT);

        assertThat(weekStartOf(east.userId()))
            .as("06:00 UTC on a Monday is already Monday in Toronto")
            .isEqualTo(TORONTO_WEEK);
        assertThat(weekStartOf(west.userId()))
            .as("and still Sunday in Vancouver, so their week ended a week earlier — this is "
                + "readiness gap row 15, and a container-clock LocalDate.now() cannot produce it")
            .isEqualTo(VANCOUVER_WEEK);
    }

    @Test
    @DisplayName("a member with AI switched off gets no note, however much ledger they have")
    void aMemberWithAiOffIsNotARecipient() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "insight-optout");

        job().runOnce(RUN_AT);

        assertThat(notesFor(member.userId()))
            .as("the recipient set is 'members whose own ai_settings.enabled is true', not "
                + "'everybody' and not 'members who have chatted' (D6)")
            .isEmpty();
    }

    @Test
    @DisplayName("a member reads their own notes and nobody else's over GET /v1/insights")
    void theRouteReturnsTheCallersOwnNotes() {
        Session mine = inviteAndRegister(loginBootstrapAdmin(), "insight-route");
        Session theirs = inviteAndRegister(loginBootstrapAdmin(), "insight-route-other");
        makeRecipient(mine, "America/Toronto", "CAD", "CA");
        makeRecipient(theirs, "America/Toronto", "CAD", "CA");

        job().runOnce(RUN_AT);

        HttpResponse<String> response = get(appUrl("/v1/insights"), mine.accessToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> notes = Json.elements(Json.data(response.body()));
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).path("content").asString()).isEqualTo(NOTE);
        assertThat(notes.get(0).path("weekStart").asString()).isEqualTo(TORONTO_WEEK.toString());
        assertThat(notes.get(0).path("delivered").asBoolean()).isFalse();
        assertThat(notes.get(0).path("deliveredAt").isNull()).isTrue();
        assertThat(notes.get(0).path("id").asString())
            .isEqualTo(idOfNote(mine.userId()));
        assertThat(Json.parse(response.body()).path("meta").path("total").asInt()).isEqualTo(1);
        assertThat(Json.parse(response.body()).path("meta").path("limit").asInt())
            .as("a client that omits limit gets the page size the shared LimitQuery parameter "
                + "documents - this route reuses that parameter, so it may not quietly serve a "
                + "different default from every other paged list")
            .isEqualTo(50);

        assertThat(notes.get(0).path("id").asString())
            .as("and it is theirs, not the other member's")
            .isNotEqualTo(idOfNote(theirs.userId()));
    }

    /** The job with only the gateway stubbed; the recipient query and the repository are real. */
    private WeeklyInsightJob job() {
        InsightService service = new InsightService(gateway, contextBuilder, prompts,
            coachProperties, insights, encryption);
        return new WeeklyInsightJob(recipients, service, clock);
    }

    /**
     * Turns {@code member} into a recipient: their own AI switched on, in a named timezone,
     * currency and jurisdiction.
     *
     * <p>Written straight to the tables. The settings row cannot be saved through
     * {@code PUT /v1/ai/settings} against a stub on loopback — {@code OutboundUrlPolicy} refuses it
     * and the integration suite cannot set {@code TROVE_EGRESS_ALLOW_PRIVATE} without forking a
     * second Spring context — and this test is about the job, not about the settings form, which
     * {@code AiSettingsIT} and {@code AiPolicyIT} already cover.
     */
    private void makeRecipient(Session member, String timezone, String currency, String country) {
        jdbcTemplate.update(
            "update users set timezone = ?, currency = ?, country = ? where id = ?",
            timezone, currency, country, member.userId());
        jdbcTemplate.update("""
            INSERT INTO ai_settings (user_id, enabled, endpoint_url, model, privacy_tier)
            VALUES (?, true, 'https://ai.example.test/v1', 'probe-model', 'LOCAL_ONLY')
            ON CONFLICT (user_id) DO UPDATE SET enabled = true
            """, member.userId());
    }

    private List<Map<String, Object>> notesFor(UUID userId) {
        return jdbcTemplate.queryForList("select * from coach_insight where user_id = ?", userId);
    }

    private LocalDate weekStartOf(UUID userId) {
        List<Map<String, Object>> rows = notesFor(userId);
        assertThat(rows).as("this member should have exactly one note").hasSize(1);
        return ((java.sql.Date) rows.get(0).get("week_start")).toLocalDate();
    }

    private String idOfNote(UUID userId) {
        return notesFor(userId).get(0).get("id").toString();
    }

    /** The user turn of whichever prompt contains {@code needle}, or null if none did. */
    private String instructionMentioning(String needle) {
        for (List<AiMessage> prompt : allPrompts()) {
            for (AiMessage message : prompt) {
                if ("user".equals(message.role()) && message.content().contains(needle)) {
                    return message.content();
                }
            }
        }
        return null;
    }

    private List<List<AiMessage>> allPrompts() {
        // The gateway records one prompt per call and the job visits every enabled member in the
        // shared database, so the caller looks for the one that names the week it cares about
        // rather than assuming a position. Never an absolute count.
        List<List<AiMessage>> prompts = new java.util.ArrayList<>();
        for (int i = 0; i < gateway.callCount(); i++) {
            prompts.add(gateway.promptAt(i));
        }
        return prompts;
    }
}
