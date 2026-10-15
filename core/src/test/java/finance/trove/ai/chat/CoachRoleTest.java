package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The role vocabulary: upper case in the database, lower case on the wire. */
class CoachRoleTest {

    @Test
    @DisplayName("the wire name is the OpenAI-compatible spelling")
    void wireNamesAreLowerCase() {
        assertThat(CoachRole.SYSTEM.wireName()).isEqualTo("system");
        assertThat(CoachRole.USER.wireName()).isEqualTo("user");
        assertThat(CoachRole.ASSISTANT.wireName()).isEqualTo("assistant");
    }

    @Test
    @DisplayName("a client can name user and assistant, and nothing else")
    void onlyTwoRolesAreAccepted() {
        assertThat(CoachRole.fromWireName("user")).isEqualTo(CoachRole.USER);
        assertThat(CoachRole.fromWireName("  ASSISTANT ")).isEqualTo(CoachRole.ASSISTANT);
        assertThat(CoachRole.fromWireName("system"))
            .as("a client that could name the system turn would be writing the prompt")
            .isNull();
        assertThat(CoachRole.fromWireName("tool"))
            .as("this step wires no tool calling of any kind")
            .isNull();
        assertThat(CoachRole.fromWireName(null)).isNull();
    }
}
