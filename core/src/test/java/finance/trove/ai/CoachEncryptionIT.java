package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.chat.CoachConversation;
import finance.trove.ai.chat.CoachConversationRepository;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.insight.CoachInsight;
import finance.trove.ai.insight.CoachInsightRepository;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.crypto.FieldEncryptionService;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The gap-row-44 gate: coach content is ciphertext at rest, and there is no plaintext copy
 * ({@code docs/recovery/07-READINESS-GAPS.md} SHOULD row 44, second half, handed to collapse step
 * 13 by name in step 17's report).
 *
 * <p><strong>This test must fail if anyone ever adds a plaintext column back.</strong> That is why
 * it does two things rather than one: it reads the stored rows with plain JDBC and asserts the
 * plaintext appears in <em>no</em> column of them, and it asks {@code information_schema} what
 * columns those tables have at all. The first catches a service that stopped encrypting; the second
 * catches a migration that added {@code content}, {@code preview} or {@code title_plain} beside the
 * encrypted column — which would pass the first check while quietly undoing the whole point.
 *
 * <p>A member's question names what they bought, where, and for how much. A weekly note names what
 * they spent it on. A database dump of these two tables must not be a transcript of that.
 */
class CoachEncryptionIT extends AuthFlowSupport {

    private static final String SECRET_QUESTION =
        "Why did my Rosedale Pharmacy prescription cost 412.55 this month?";
    private static final String SECRET_ANSWER =
        "Your Rosedale Pharmacy total rose because of a 200.00 dispensing fee.";
    private static final String SECRET_NOTE =
        "You spent 412.55 less than last week, mostly at Rosedale Pharmacy.";

    @Autowired
    private CoachConversationService conversations;

    @Autowired
    private CoachConversationRepository conversationRepository;

    @Autowired
    private CoachInsightRepository insights;

    @Autowired
    private FieldEncryptionService encryption;

    @Test
    @DisplayName("a stored turn is v1: ciphertext and its plaintext appears in no column of the row")
    void conversationContentIsCiphertextAtRest() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-crypto");
        CoachConversation conversation =
            conversations.startOrResume(member.userId(), null);
        conversations.appendUserMessage(member.userId(), conversation.getId(), SECRET_QUESTION);
        conversations.appendAssistantMessage(member.userId(), conversation.getId(), SECRET_ANSWER,
            "qwen2.5:14b");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select * from coach_message where conversation_id = ? order by created_at",
            conversation.getId());
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat((String) row.get("content_encrypted"))
                .as("FieldEncryptionService output, version prefix and all")
                .startsWith(FieldEncryptionService.CURRENT_VERSION + ":");
            assertThat(everyValueOf(row))
                .as("no column of this row carries the plaintext — not a copy, not a preview, "
                    + "not a derived title (gap row 44)")
                .doesNotContain("Rosedale Pharmacy")
                .doesNotContain("412.55")
                .doesNotContain("dispensing fee");
        }
        assertThat(encryption.decrypt((String) rows.get(0).get("content_encrypted")))
            .as("and it really is the question, encrypted — not something else")
            .isEqualTo(SECRET_QUESTION);

        assertThat(jdbcTemplate.queryForObject(
            "select coalesce(title, '') from coach_conversation where id = ?", String.class,
            conversation.getId()))
            .as("no title is derived from the first message: that derivation IS the plaintext "
                + "copy this column exists to avoid")
            .isEmpty();
    }

    @Test
    @DisplayName("a weekly insight is ciphertext at rest too")
    void insightContentIsCiphertextAtRest() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "insight-crypto");
        LocalDate weekStart = LocalDate.of(2019, 3, 4);

        assertThat(insights.insertIfAbsent(member.userId(), weekStart,
            encryption.encrypt(SECRET_NOTE))).isOne();

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select * from coach_insight where user_id = ? and week_start = ?",
            member.userId(), weekStart);
        assertThat((String) row.get("content_encrypted"))
            .startsWith(FieldEncryptionService.CURRENT_VERSION + ":");
        assertThat(everyValueOf(row))
            .doesNotContain("Rosedale Pharmacy")
            .doesNotContain("412.55");

        assertThat(insights.insertIfAbsent(member.userId(), weekStart,
            encryption.encrypt("a second note for the same week")))
            .as("UNIQUE (user_id, week_start) plus ON CONFLICT DO NOTHING: a second run of the "
                + "weekly job writes nothing")
            .isZero();
    }

    @Test
    @DisplayName("neither table has a plaintext content column, now or after any later migration")
    void thereIsNoPlaintextColumnAnywhere() {
        assertThat(columnsOf("coach_message"))
            .as("the encrypted column is the only place content lives")
            .contains("content_encrypted")
            .doesNotContain("content", "content_plain", "preview", "content_preview", "body",
                "text", "message");
        assertThat(columnsOf("coach_insight"))
            .contains("content_encrypted")
            .doesNotContain("content", "insight_text", "content_plain", "preview", "summary");
        assertThat(columnsOf("coach_conversation"))
            .as("a conversation carries a member-set title and nothing derived from its messages")
            .doesNotContain("content", "content_encrypted", "preview", "first_message",
                "last_message");
    }

    @Test
    @DisplayName("the owner reads their own plaintext back, and nobody else can")
    void onlyTheOwnerSeesPlaintext() {
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "coach-crypto-mate");
        CoachConversation conversation = conversations.startOrResume(admin.userId(), null);
        conversations.appendUserMessage(admin.userId(), conversation.getId(), SECRET_QUESTION);

        HttpResponse<String> owner = get(
            appUrl("/v1/chat/history?sessionId=" + conversation.getId()), admin.accessToken());
        assertThat(owner.statusCode()).as(owner.body()).isEqualTo(200);
        List<JsonNode> turns = Json.elements(Json.data(owner.body()));
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).path("content").asString())
            .as("decryption happens in the service, for the owner, and produces the real text")
            .isEqualTo(SECRET_QUESTION);

        HttpResponse<String> theirs = get(
            appUrl("/v1/chat/history?sessionId=" + conversation.getId()), mate.accessToken());
        assertThat(theirs.statusCode()).isEqualTo(404);
        assertThat(theirs.body()).doesNotContain("Rosedale Pharmacy");

        assertThat(conversationRepository.findByIdAndUserId(conversation.getId(), mate.userId()))
            .as("the repository is scoped too — there is no unscoped finder to reach for")
            .isEmpty();

        CoachConversation owned = conversationRepository
            .findByIdAndUserId(conversation.getId(), admin.userId()).orElseThrow();
        assertThat(owned.getUserId()).isEqualTo(admin.userId());
        assertThat(owned.getCreatedAt()).isNotNull();
        assertThat(owned.getUpdatedAt()).isNotNull();
        assertThat(owned.getTitle())
            .as("never derived from a message; a member may set one, and none does today")
            .isNull();
    }

    @Test
    @DisplayName("an insight row reads back through its own scoped repository, ciphertext and all")
    void insightsAreReachableOnlyThroughTheirOwner() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "insight-crypto-read");
        Session other = inviteAndRegister(loginBootstrapAdmin(), "insight-crypto-other");
        LocalDate weekStart = LocalDate.of(2019, 3, 11);
        insights.insertIfAbsent(member.userId(), weekStart, encryption.encrypt(SECRET_NOTE));

        List<CoachInsight> mine = insights.findAllByUserId(member.userId());
        assertThat(mine).hasSize(1);
        CoachInsight note = mine.get(0);
        assertThat(note.getUserId()).isEqualTo(member.userId());
        assertThat(note.getWeekStart()).isEqualTo(weekStart);
        assertThat(note.isDelivered())
            .as("nothing has delivered it; GET /v1/insights will not set this either")
            .isFalse();
        assertThat(note.getDeliveredAt()).isNull();
        assertThat(note.getCreatedAt()).isNotNull();
        assertThat(note.getContentEncrypted())
            .as("the entity exposes the stored value under the name it has; there is no accessor "
                + "that returns decrypted content")
            .startsWith(FieldEncryptionService.CURRENT_VERSION + ":");

        assertThat(insights.findByIdAndUserId(note.getId(), other.userId()))
            .as("another member cannot reach it by its id — the base has no unscoped finder")
            .isEmpty();
        assertThat(insights.findAllByUserId(other.userId())).isEmpty();
    }

    /** Every column of a row, joined, so one assertion covers columns nobody thought of. */
    private static String everyValueOf(Map<String, Object> row) {
        StringBuilder text = new StringBuilder();
        row.forEach((column, value) ->
            text.append(column).append('=').append(value).append('\n'));
        return text.toString();
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
            "select column_name from information_schema.columns "
                + "where table_schema = 'public' and table_name = ?", String.class, table);
    }
}
