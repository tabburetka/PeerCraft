package net.peercraft.rendezvous.account;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous SMTP, with mandatory TLS and bounded connect/read/write times. */
public final class SmtpMailSender implements EmailRecoveryService.MailSender, AutoCloseable {
    private final EmailConfig config;
    private final Session session;
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "peercraft-email"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong failures = new AtomicLong();
    public SmtpMailSender(EmailConfig config) {
        if (!config.enabled()) throw new IllegalArgumentException("SMTP disabled");
        this.config = config;
        Properties p = new Properties();
        p.setProperty("mail.smtp.auth", "true");
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        p.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        p.setProperty("mail.smtp.ssl.enable", Boolean.toString(config.implicitTls()));
        p.setProperty("mail.smtp.starttls.enable", Boolean.toString(!config.implicitTls()));
        p.setProperty("mail.smtp.starttls.required", Boolean.toString(!config.implicitTls()));
        for (String timeout : new String[]{"connectiontimeout", "timeout", "writetimeout"})
            p.setProperty("mail.smtp." + timeout, "10000");
        session = Session.getInstance(p);
    }
    @Override public boolean enqueue(EmailRecoveryService.Mail mail) {
        try { queue.execute(() -> send(mail)); return true; }
        catch (RejectedExecutionException full) { failures.incrementAndGet(); return false; }
    }
    private void send(EmailRecoveryService.Mail mail) {
        try (Transport transport = session.getTransport("smtp")) {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.from(), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(mail.recipient(), true));
            message.setSubject(mail.subject(), StandardCharsets.UTF_8.name());
            message.setText(mail.body(), StandardCharsets.UTF_8.name());
            message.saveChanges();
            transport.connect(config.smtpHost(), config.smtpPort(), config.smtpUser(), config.smtpPassword());
            transport.sendMessage(message, message.getAllRecipients());
        } catch (Exception unavailable) {
            failures.incrementAndGet(); // Do not log recipient, codes, provider replies or credentials.
        }
    }
    public long failedDeliveries() { return failures.get(); }
    @Override public void close() { queue.shutdownNow(); }
}
