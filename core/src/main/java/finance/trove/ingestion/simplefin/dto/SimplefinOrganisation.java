package finance.trove.ingestion.simplefin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The institution a SimpleFIN account belongs to, as the bridge names it.
 *
 * <p>Only the display name is read. The bridge also reports a domain and a URL; neither is stored,
 * and neither is used to make any outbound call — everything Trove connects to goes through
 * {@code OutboundUrlPolicy} and the pinned client factory, never through a name a third party put
 * in a response body.
 *
 * @param name   the institution's display name, or null
 * @param domain the institution's domain, read so an unknown-property setting cannot hide a typo;
 *               never used as a destination
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplefinOrganisation(String name, String domain) {
}
