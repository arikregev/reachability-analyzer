package io.github.arikregev.reach.api.kafka;

import io.github.arikregev.reach.api.intake.Caller;
import io.github.arikregev.reach.api.intake.IntakeMetrics;
import io.github.arikregev.reach.api.intake.IntakeService;
import io.github.arikregev.reach.api.intake.NoPriorityClassException;
import io.github.arikregev.reach.api.intake.RequestRejectedException;
import io.github.arikregev.reach.api.intake.SubmitResult;
import io.github.arikregev.reach.api.store.StoreUnavailableException;
import io.github.arikregev.reach.contracts.ParseResult;
import io.github.arikregev.reach.contracts.ScanRequestParser;
import io.github.arikregev.reach.contracts.Violation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.kafka.common.header.Headers;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

/**
 * Handles one request record: invalid records go to the DLQ, valid ones become scans. Postgres outages are retried
 * with backoff; when the retries run out the exception reaches the channel, which stops (failure-strategy=fail) so
 * no record is lost (D-006). A failure at commit (a rollback during a Postgres failover) counts as an outage too,
 * and so does a missing priority class, which affects every record alike. Any other failure is specific to the
 * record, so it goes to the DLQ as {@code UNPROCESSABLE} instead of stopping intake for every later record.
 */
@ApplicationScoped
public class KafkaRecordHandler {

    private static final Logger LOG = Logger.getLogger(KafkaRecordHandler.class);
    static final String UNPROCESSABLE = "UNPROCESSABLE";

    private final ScanRequestParser parser;
    private final IntakeService intake;
    private final DlqPublisher dlq;
    private final IntakeMetrics metrics;

    public KafkaRecordHandler(ScanRequestParser parser, IntakeService intake, DlqPublisher dlq, IntakeMetrics metrics) {
        this.parser = parser;
        this.intake = intake;
        this.dlq = dlq;
        this.metrics = metrics;
    }

    @Retry(retryOn = {StoreUnavailableException.class, NoPriorityClassException.class}, maxRetries = -1, maxDuration = 5, durationUnit = ChronoUnit.MINUTES,
            delay = 500, jitter = 250)
    @ExponentialBackoff(maxDelay = 30_000)
    public void handle(byte[] key, byte[] value, Headers headers) {
        ParseResult parsed = parser.parse(value);
        if (parsed instanceof ParseResult.Invalid invalid) {
            metrics.rejected(Caller.Source.KAFKA, invalid.code().name());
            dlq.publish(key, value, headers, invalid.code().name(), invalid.violations(), invalid.schemaVersion());
            return;
        }
        ParseResult.Valid valid = (ParseResult.Valid) parsed;
        try {
            SubmitResult r = intake.submit(valid.request(), Caller.kafka());
            metrics.accepted(Caller.Source.KAFKA, r.duplicate());
            LOG.debugf("Kafka request %s -> scan %s (duplicate=%s)", r.requestUid(), r.scanId(), r.duplicate());
        } catch (RequestRejectedException e) {
            metrics.rejected(Caller.Source.KAFKA, "SCHEMA_INVALID");
            dlq.publish(key, value, headers, "SCHEMA_INVALID", e.violations(), "1");
        } catch (StoreUnavailableException e) {
            throw e;
        } catch (NoPriorityClassException e) {
            LOG.error(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            if (StoreUnavailableException.isOutage(e)) {
                throw new StoreUnavailableException(e);
            }
            LOG.errorf(e, "Kafka request %s could not be processed; sending it to the DLQ", valid.request().requestUid());
            metrics.rejected(Caller.Source.KAFKA, UNPROCESSABLE);
            dlq.publish(key, value, headers, UNPROCESSABLE,
                    List.of(new Violation("", "request could not be processed: " + e.getClass().getSimpleName())), "1");
        }
    }
}
