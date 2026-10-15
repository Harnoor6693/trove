package finance.trove.ingestion;

import java.time.LocalDate;
import java.util.List;

/**
 * One way an uploaded file's dates could be read, with the values that make the question
 * unanswerable from the data alone ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p>{@code 03/09/2026} is the third of September to a Canadian bank and the ninth of March to an
 * American one, and no amount of sniffing can tell which a given export meant: both readings
 * produce a valid date for every row. Guessing silently is how an import lands a household's
 * September spending in March. So the preview offers the candidates, the member picks one, and the
 * commit uses what came back.
 *
 * @param format           a {@code java.time} pattern to send back on the commit
 * @param sampleValues     date cells taken from the file, in the order they appear
 * @param interpretedDates what those same cells mean under {@link #format}, position for position
 */
public record DateFormatCandidate(String format, List<String> sampleValues,
                                  List<LocalDate> interpretedDates) {

    public DateFormatCandidate {
        sampleValues = List.copyOf(sampleValues);
        interpretedDates = List.copyOf(interpretedDates);
    }
}
