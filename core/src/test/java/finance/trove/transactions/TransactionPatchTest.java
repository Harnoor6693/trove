package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.exception.ValidationException;
import finance.trove.transactions.dto.TransactionPatch;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link TransactionPatch}: the one place in this API where <strong>presence</strong> is part of
 * the contract ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>Absent means "leave it alone" and an explicit {@code null} means "clear it". A record bound by
 * Jackson delivers both as a null field and cannot tell them apart, which would make unlinking a
 * transfer inexpressible — so the body is read as a tree and the distinction is two named booleans
 * rather than a convention.
 */
class TransactionPatchTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("An absent field and a null field are different requests")
    void absentAndNullAreDistinguished() {
        TransactionPatch absent = parse("{}");
        assertThat(absent.hasNotes()).isFalse();
        assertThat(absent.hasTransferGroupId()).isFalse();
        assertThat(absent.isEmpty()).isTrue();

        TransactionPatch explicitNull = parse("{\"notes\": null, \"transferGroupId\": null}");
        assertThat(explicitNull.hasNotes()).isTrue();
        assertThat(explicitNull.notes()).isNull();
        assertThat(explicitNull.hasTransferGroupId()).isTrue();
        assertThat(explicitNull.transferGroupId()).isNull();
        assertThat(explicitNull.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("Every field round-trips with its value")
    void everyFieldIsRead() {
        UUID category = UUID.randomUUID();
        UUID group = UUID.randomUUID();

        TransactionPatch patch = parse("{\"categoryId\":\"" + category + "\","
            + "\"merchantName\":\" Blue Door Coffee \",\"notes\":\"A note\","
            + "\"tags\":[\"travel\",\"work\"],\"transferGroupId\":\"" + group + "\"}");

        assertThat(patch.categoryId()).isEqualTo(category);
        assertThat(patch.merchantName()).isEqualTo("Blue Door Coffee");
        assertThat(patch.notes()).isEqualTo("A note");
        assertThat(patch.tags()).containsExactly("travel", "work");
        assertThat(patch.transferGroupId()).isEqualTo(group);
    }

    @Test
    @DisplayName("An all-whitespace string is a request to clear, not a blank to store")
    void whitespaceClears() {
        TransactionPatch patch = parse("{\"notes\":\"   \",\"merchantName\":\"\"}");

        assertThat(patch.hasNotes()).isTrue();
        assertThat(patch.notes()).isNull();
        assertThat(patch.merchantName()).isNull();
    }

    @Test
    @DisplayName("Tags are trimmed, de-duplicated and stripped of blanks")
    void tagsAreNormalised() {
        TransactionPatch patch = parse("{\"tags\":[\" travel \",\"travel\",\"\",\"work\"]}");

        assertThat(patch.tags()).containsExactly("travel", "work");
    }

    @Test
    @DisplayName("A field of the wrong type is a 400, never a 500")
    void wrongTypesAreRejected() {
        assertThatThrownBy(() -> parse("{\"tags\": 7}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"tags\": [1, 2]}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"notes\": 7}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"categoryId\": 7}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"categoryId\": \"not-a-uuid\"}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("[]"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionPatch.from(null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Over-long values and over-full tag lists are refused")
    void boundsAreEnforced() {
        assertThatThrownBy(() -> parse("{\"merchantName\":\"" + "x".repeat(256) + "\"}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"notes\":\"" + "x".repeat(2001) + "\"}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"tags\":[\"" + "x".repeat(51) + "\"]}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"tags\":[" + "\"t\",".repeat(50) + "\"t51\"]}"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("transferPartnerId is read when present and is a request on its own")
    void transferPartnerIdIsRead() {
        UUID partner = UUID.randomUUID();

        TransactionPatch patch = parse("{\"transferPartnerId\":\"" + partner + "\"}");

        assertThat(patch.hasTransferPartnerId()).isTrue();
        assertThat(patch.transferPartnerId()).isEqualTo(partner);
        assertThat(patch.hasTransferGroupId()).isFalse();
        assertThat(patch.isEmpty()).as("a link request is not a no-op").isFalse();
        assertThat(parse("{}").hasTransferPartnerId()).isFalse();
    }

    @Test
    @DisplayName("transferPartnerId must name a row: null, a non-string and a non-uuid are 400")
    void transferPartnerIdMustBeAUuid() {
        assertThatThrownBy(() -> parse("{\"transferPartnerId\": null}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"transferPartnerId\": 7}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"transferPartnerId\": \"not-a-uuid\"}"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("transferPartnerId and transferGroupId together are 400: the server mints the id")
    void transferPartnerIdExcludesTransferGroupId() {
        UUID partner = UUID.randomUUID();
        assertThatThrownBy(() -> parse("{\"transferPartnerId\":\"" + partner + "\","
            + "\"transferGroupId\":\"" + UUID.randomUUID() + "\"}"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse("{\"transferPartnerId\":\"" + partner + "\","
            + "\"transferGroupId\":null}"))
            .isInstanceOf(ValidationException.class);
    }

    private static TransactionPatch parse(String json) {
        return TransactionPatch.from(MAPPER.readTree(json));
    }
}
