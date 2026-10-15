package finance.trove.app.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs one {@link AdminConsole} command and stops the application.
 *
 * <p>Separate from {@link AdminConsole} so that the commands themselves are a plain object with no
 * lifecycle: this class owns the two things that make a process a command-line tool — the
 * condition that decides whether it is one, and the exit.
 *
 * <p><strong>The condition is the safety property.</strong> The bean exists only when
 * {@code trove.cli.command} is set, which can only come from the process's own arguments or
 * environment. An ordinary boot has no such property, so this class is not instantiated at all and
 * there is no request, header or body that can reach a console command.
 *
 * <p>Output goes to {@code System.out} rather than to the logger on purpose. A reset link is the
 * one thing this application prints that must <em>not</em> be logged: it is a credential, and the
 * whole point of {@code RequestPath} is that credentials do not go into log files where a shipper
 * will keep them. Standard output belongs to the operator who typed the command.
 */
@Component
@ConditionalOnProperty(name = "trove.cli.command")
public class AdminConsoleRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminConsoleRunner.class);

    private final AdminConsole console;
    private final ConfigurableApplicationContext context;
    private final String command;

    public AdminConsoleRunner(AdminConsole console, ConfigurableApplicationContext context,
                              @Value("${trove.cli.command}") String command) {
        this.console = console;
        this.context = context;
        this.command = command;
    }

    @Override
    public void run(ApplicationArguments args) {
        final int exitCode = runCommand();
        // The process exists to run this one command. Leaving the web server up afterwards would
        // bind the port a real instance wants and leave the operator with a container that never
        // returns.
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runCommand() {
        try {
            return console.execute(command, System.out::println);
        } catch (RuntimeException e) {
            // Every failure is the operator's to read, so it is printed as well as logged: they
            // are looking at a terminal, not at a log aggregator.
            System.out.println(e.getMessage());
            LOG.error("Console command failed", e);
            return AdminConsole.FAILURE;
        }
    }
}
