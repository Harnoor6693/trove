package finance.trove.notifications.send;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import jakarta.mail.internet.MimeMessage;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * SMTP delivery ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3).
 *
 * <h2>Built, and deliberately off</h2>
 * <strong>There is no owner decision cutting email.</strong> Decision 8 of 2026-09-03 cuts PUSH and
 * nothing else; §2.8 and §6 D3 both keep EMAIL, §9 risk 13's test is an SMTP stub, and
 * {@code core/pom.xml} already carries {@code spring-boot-starter-mail} and
 * {@code spring-boot-starter-thymeleaf} for exactly this class. The configuration is in place:
 * {@code spring.mail.*} in {@code application.yml} (from SMTP_HOST, SMTP_PORT, SMTP_USER,
 * SMTP_PASSWORD) and the SMTP block in {@code core/.env.example}. This bean exists only when
 * {@code trove.notifications.smtp-enabled} is true (SMTP_ENABLED=true, default false), which is
 * {@code @ConditionalOnProperty} with {@code matchIfMissing = false}.
 *
 * <p>A bean that is absent is better than a bean that silently drops everything. The source's
 * channel logged "SMTP not configured, skipping" and returned normally, and the dispatcher then
 * recorded the delivery as {@code SENT} — which is finding D3.
 *
 * <h2>The template allowlist</h2>
 * The source passed a caller-supplied template name to {@code TemplateEngine.process}, guarded by
 * a five-name allowlist. The names now come from {@link NotificationContent}, which is only ever
 * built inside this application, and the allowlist is kept anyway: a template name reaching
 * Thymeleaf is a server-side template injection primitive if it ever becomes reachable from a
 * request, and the check costs a set lookup. {@code tax-reminder} is not in it — that template is
 * deleted with the tax engine (defect D-N3).
 */
@Component
@ConditionalOnProperty(name = NotificationProperties.SMTP_ENABLED_PROPERTY,
    havingValue = "true", matchIfMissing = false)
public class EmailChannel implements DeliveryChannel {

    private static final Logger LOG = LoggerFactory.getLogger(EmailChannel.class);

    /**
     * The only template this channel will render, and the only one that exists.
     *
     * <p>The source shipped five and allowlisted them all. {@code tax-reminder} is deleted with
     * the tax engine (defect D-N3); {@code budget-alert}, {@code subscription-alert} and
     * {@code sync-status} are not ported because their producers name no template — each of those
     * notifications is one sentence, and the escaped plain-text fallback below renders it with
     * nothing to keep in step with a changing model.
     */
    private static final Set<String> ALLOWED_TEMPLATES = Set.of("weekly-digest");

    private final JavaMailSender mailSender;
    private final TemplateEngine templates;
    private final NotificationProperties properties;
    private final UserRepository users;

    public EmailChannel(JavaMailSender mailSender, TemplateEngine templates,
                        NotificationProperties properties, UserRepository users) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.properties = properties;
        this.users = users;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    /**
     * On only when the member asked for it AND gave an address.
     *
     * <p>Both halves matter: a member may enable the channel in a settings form before typing an
     * address, and refusing that write would make the two fields order-dependent. The channel
     * simply does not run until there is somewhere to send to.
     *
     * <p>B3-07: and only to the member's own sign-in address. Nothing proves a member owns any
     * other address (there is no verification link, because email is off by default), so mailing
     * whatever was typed into preferences would let a member point alerts and digests at a third
     * party. Any other address is kept and skipped until a verification flow exists.
     */
    @Override
    public boolean enabledFor(NotificationPreference preference) {
        String address = preference.getEmailAddress();
        return preference.isEmailEnabled() && address != null && !address.isBlank()
            && users.findAccount(preference.getUserId())
                .filter(User::isActive)
                .map(User::getEmail)
                .filter(address.trim()::equalsIgnoreCase)
                .isPresent();
    }

    @Override
    public DeliveryResult send(UUID userId, NotificationType type,
                               NotificationPreference preference, NotificationContent content) {

        String recipient = preference.getEmailAddress();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("\"" + properties.fromName() + "\" <" + properties.fromAddress() + ">");
            helper.setTo(recipient);
            helper.setSubject(content.subject());
            helper.setText(render(content), true);
            mailSender.send(message);
            return DeliveryResult.sent();
        } catch (Exception e) {
            // swallow-ok: the failure is not swallowed, it is RETURNED. This is the D3 fix: an
            // SMTP refusal is an ordinary outcome rather than a programming error, and the caller
            // writes it into notification_log.error_message. Rethrowing would abort the whole
            // notification and lose the inbox row that is meant to survive a channel failure.
            // The exception's type only: a mail exception's message can carry the recipient.
            LOG.warn("email delivery to user {} failed for {}: {}", userId, type,
                e.getClass().getSimpleName());
            return DeliveryResult.failed(e);
        }
    }

    /**
     * The HTML body: a Thymeleaf template when the producer named an allowed one, and the escaped
     * plain-text body otherwise.
     *
     * <p>{@code HtmlUtils.htmlEscape} on the fallback path is not decoration. The body carries
     * merchant names, which arrive from a bank feed or a CSV somebody uploaded, and an unescaped
     * one would be markup in every recipient's mail client.
     */
    String render(NotificationContent content) {
        String template = content.templateName();
        if (template != null && ALLOWED_TEMPLATES.contains(template)) {
            Context context = new Context(Locale.ENGLISH);
            content.templateModel().forEach(context::setVariable);
            context.setVariable("subject", content.subject());
            context.setVariable("body", content.body());
            return templates.process("email/" + template, context);
        }
        if (template != null) {
            LOG.warn("refusing to render notification template '{}': not on the allowlist",
                template);
        }
        return "<html><body><p>" + HtmlUtils.htmlEscape(content.body()) + "</p></body></html>";
    }
}
