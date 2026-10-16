package finance.trove.receipts.pipeline.stages;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.taxsplit.TaxSplit;
import finance.trove.receipts.taxsplit.TaxSplitService;
import org.springframework.stereotype.Component;

/**
 * Stage 5 — the Canadian tax split ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, open
 * question 1).
 *
 * <p><strong>Canada only, from the member's own row.</strong> The source took the province from an
 * {@code X-Trove-Province} <em>request header</em> and applied a configured default to anything it
 * did not recognise — a client-supplied input deciding a tax calculation, with a plausible-looking
 * wrong answer as the failure mode. Here the country and the province both come from the
 * uploader's {@code users} row, and a member whose country is not {@code CA} gets no split at all
 * rather than a zeroed one that looks computed.
 *
 * <p>(§2.6 says the province lives on {@code user_preferences.region}. That table was never
 * written: V10 is unused and V3 put {@code region}, {@code locale}, {@code timezone} and
 * {@code onboarding_completed} on {@code users}. The merged schema is the fact.)
 *
 * <p><strong>The result stays on the receipt.</strong> It is a convenience for the member reading
 * this one screen and is never part of the tax export — the tax-time export carries the member's
 * own amounts and labels with no rate and no computed figure of any kind (§2.9, §4.12), and step
 * 15's {@code TaxExportIT} greps the generated files for {@code gst}, {@code tax_owing} and {@code
 * rate} to hold that. If this stage ever makes that test go red, a computed number has reached an
 * export and the answer is to stop, not to change the test. The full data export does carry the
 * stored split in {@code receipts.json}.
 */
@Component
public class TaxSplitStage implements PipelineStage {

    private final TaxSplitService taxSplit;

    public TaxSplitStage(TaxSplitService taxSplit) {
        this.taxSplit = taxSplit;
    }

    @Override
    public String stageName() {
        return "TAX_SPLIT";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {

        if (context.country() != Jurisdiction.CA) {
            // Not a Canadian member: no split, and no zeros either. Leaving the three columns null
            // is the difference between "does not apply" and "computed to be nothing".
            return;
        }
        if (receipt.getExtractedAmount() == null) {
            return;
        }
        TaxSplit split = taxSplit.split(receipt.getExtractedAmount(), context.region());
        receipt.setTaxSplit(split.gst(), split.pst(), split.hst());
    }
}
