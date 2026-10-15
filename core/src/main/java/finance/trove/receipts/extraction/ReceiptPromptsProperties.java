package finance.trove.receipts.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The receipt vision prompt, bound from {@code classpath:prompts/receipt.yml}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6; {@link ReceiptPromptsConfig} is what makes the
 * file a property source).
 *
 * <p>The prompt lives in a resource rather than inline in Java because §2.6 moves it there, and
 * nothing about receipts is added to {@code application.yml}: collapse steps 11, 13, 14 and 16 run
 * in parallel worktrees and no step of this wave owns that file.
 *
 * <p>A record validated in its canonical constructor, which {@code ArchitectureTest} requires: a
 * missing prompt fails the context refresh rather than sending a bare image with no instructions
 * to somebody's model and getting an unparseable answer back.
 *
 * @param extract the single prompt the vision stage sends alongside the image
 */
@ConfigurationProperties("trove.receipt.prompt")
public record ReceiptPromptsProperties(String extract) {

    public ReceiptPromptsProperties {
        if (extract == null || extract.isBlank()) {
            throw new IllegalArgumentException(
                "trove.receipt.prompt.extract is missing: prompts/receipt.yml is not on the "
                    + "classpath, or ReceiptPromptsConfig's @PropertySource no longer points at "
                    + "it. The vision stage would otherwise send an image with no instructions");
        }
    }
}
