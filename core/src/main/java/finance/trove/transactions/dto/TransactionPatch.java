package finance.trove.transactions.dto;

import finance.trove.common.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The parsed body of {@code PATCH /v1/transactions/{id}}, with the one thing a plain DTO cannot
 * express: <strong>whether a field was present at all</strong>
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4; gap P2-14).
 *
 * <p>That distinction is the whole reason this method is a PATCH. Unlinking a transfer means
 * sending {@code {"transferGroupId": null}}, and leaving a transfer alone while changing a note
 * means sending a body that does not mention {@code transferGroupId}. Bound into a record, both
 * arrive as a null field and the server cannot tell them apart — so it would either be unable to
 * clear anything, or would clear everything the caller did not mention. Neither is a partial
 * update.
 *
 * <p>Jackson has ways to model this ({@code JsonNullable}, {@code Optional} components), and each
 * of them puts a wrapper type into the DTO that every reader then has to unwrap. Reading the tree
 * once, here, keeps the wrapper out of the domain and makes "present" and "null" two plainly
 * named booleans instead of a convention.
 *
 * <p>Validation happens during parsing, so a {@code TransactionPatch} that exists is one whose
 * every present field is of the right type and within its bounds.
 */
public final class TransactionPatch {

    /** Matches the column widths in V19 and the contract's {@code maxLength}s. */
    private static final int MAX_MERCHANT = 255;
    private static final int MAX_NOTES = 2000;
    private static final int MAX_TAG = 50;
    private static final int MAX_TAGS = 50;

    private final boolean categoryIdPresent;
    private final UUID categoryId;
    private final boolean merchantNamePresent;
    private final String merchantName;
    private final boolean notesPresent;
    private final String notes;
    private final boolean tagsPresent;
    private final List<String> tags;
    private final boolean transferGroupIdPresent;
    private final UUID transferGroupId;

    private TransactionPatch(boolean categoryIdPresent, UUID categoryId,
                             boolean merchantNamePresent, String merchantName,
                             boolean notesPresent, String notes, boolean tagsPresent,
                             List<String> tags, boolean transferGroupIdPresent,
                             UUID transferGroupId) {
        this.categoryIdPresent = categoryIdPresent;
        this.categoryId = categoryId;
        this.merchantNamePresent = merchantNamePresent;
        this.merchantName = merchantName;
        this.notesPresent = notesPresent;
        this.notes = notes;
        this.tagsPresent = tagsPresent;
        this.tags = tags == null ? null : List.copyOf(tags);
        this.transferGroupIdPresent = transferGroupIdPresent;
        this.transferGroupId = transferGroupId;
    }

    /**
     * Reads the request body.
     *
     * @throws ValidationException if the body is not a JSON object, or a present field is of the
     *                             wrong type or outside its bounds
     */
    public static TransactionPatch from(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ValidationException("The request body must be a JSON object.");
        }
        return new TransactionPatch(
            body.has("categoryId"), uuid(body, "categoryId"),
            body.has("merchantName"), text(body, "merchantName", MAX_MERCHANT),
            body.has("notes"), text(body, "notes", MAX_NOTES),
            body.has("tags"), tags(body),
            body.has("transferGroupId"), uuid(body, "transferGroupId"));
    }

    /** Whether the body asked for nothing at all. {@code {}} is a valid no-op, not an error. */
    public boolean isEmpty() {
        return !categoryIdPresent && !merchantNamePresent && !notesPresent && !tagsPresent
            && !transferGroupIdPresent;
    }

    public boolean hasCategoryId() {
        return categoryIdPresent;
    }

    /** {@code null} when the field was present and explicitly null — a request to clear it. */
    public UUID categoryId() {
        return categoryId;
    }

    public boolean hasMerchantName() {
        return merchantNamePresent;
    }

    public String merchantName() {
        return merchantName;
    }

    public boolean hasNotes() {
        return notesPresent;
    }

    public String notes() {
        return notes;
    }

    public boolean hasTags() {
        return tagsPresent;
    }

    public List<String> tags() {
        return tags;
    }

    public boolean hasTransferGroupId() {
        return transferGroupIdPresent;
    }

    public UUID transferGroupId() {
        return transferGroupId;
    }

    private static UUID uuid(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ValidationException(field + " must be a UUID string or null");
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field + " is not a valid UUID", e);
        }
    }

    private static String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ValidationException(field + " must be a string or null");
        }
        String trimmed = value.asString().trim();
        if (trimmed.length() > maxLength) {
            throw new ValidationException(
                field + " must be at most " + maxLength + " characters");
        }
        // An all-whitespace value is a request to clear, not a request to store a blank.
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> tags(JsonNode body) {
        JsonNode value = body.path("tags");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new ValidationException("tags must be an array of strings or null");
        }
        if (value.size() > MAX_TAGS) {
            throw new ValidationException("tags must hold at most " + MAX_TAGS + " entries");
        }
        List<String> parsed = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new ValidationException("tags must be an array of strings or null");
            }
            String tag = element.asString().trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > MAX_TAG) {
                throw new ValidationException(
                    "each tag must be at most " + MAX_TAG + " characters");
            }
            if (!parsed.contains(tag)) {
                parsed.add(tag);
            }
        }
        // An array that held only blanks is the same request as an empty array: no tags.
        return parsed;
    }
}
