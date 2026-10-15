package finance.trove.categorisation;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stands in front of the real AI tier and records whether a database transaction was open on the
 * calling thread when the model call was made (B1-12). It always delegates, so every other test
 * sees the real tier's behaviour.
 */
@Component
@Primary
public class RecordingAiCategorisationTier implements AiCategorisationTier {

    private final DefaultAiCategorisationTier delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger callsInsideTransaction = new AtomicInteger();

    public RecordingAiCategorisationTier(DefaultAiCategorisationTier delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<CategorisationResult> categorise(UUID householdId, UUID userId,
                                                     String merchant) {
        calls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            callsInsideTransaction.incrementAndGet();
        }
        return delegate.categorise(householdId, userId, merchant);
    }

    public void reset() {
        calls.set(0);
        callsInsideTransaction.set(0);
    }

    public int calls() {
        return calls.get();
    }

    public int callsInsideTransaction() {
        return callsInsideTransaction.get();
    }
}
