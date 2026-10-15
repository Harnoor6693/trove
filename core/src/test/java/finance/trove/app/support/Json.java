package finance.trove.app.support;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal JSON helpers for tests.
 *
 * <p>Deliberately a plain {@code JsonMapper} rather than the application's configured bean: an
 * integration test that parsed responses with the same customised mapper that produced them could
 * not detect a serialisation setting that is wrong in both directions. This one reads the wire as
 * a client would.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    /** The {@code data} of an {@code ApiResponse}; fails loudly if the envelope is not there. */
    public static JsonNode data(String json) {
        JsonNode root = parse(json);
        if (!root.has("success")) {
            throw new AssertionError("Not an ApiResponse envelope: " + json);
        }
        return root.path("data");
    }

    /** The trailing segment of {@code error.type}, which is the code both clients switch on. */
    public static String errorCode(String json) {
        String type = parse(json).path("error").path("type").asString("");
        int slash = type.lastIndexOf('/');
        return slash < 0 ? type : type.substring(slash + 1);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Builds a JSON object from alternating key/value pairs. Values may be null. */
    public static String object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be pairs");
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return MAPPER.writeValueAsString(map);
    }

    public static List<JsonNode> elements(JsonNode array) {
        return java.util.stream.StreamSupport
            .stream(array.spliterator(), false)
            .toList();
    }
}
