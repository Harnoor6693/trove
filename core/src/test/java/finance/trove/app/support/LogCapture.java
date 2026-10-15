package finance.trove.app.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Collects everything the application logs while it is open.
 *
 * <p>It attaches to the <strong>root</strong> logger, not to one named class, because the
 * assertion it exists to support is "this secret appears in no log line at all" — an assertion
 * that a per-class appender would quietly narrow to "not in the line I already thought of".
 *
 * <p>The integration tests run the application in the same JVM as the test, so this sees the
 * real log events of the real request.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    /** Starts capturing. Use in a try-with-resources so the appender is always detached. */
    public static LogCapture start() {
        return new LogCapture();
    }

    /** Every message logged so far, already formatted with its arguments substituted. */
    public List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
