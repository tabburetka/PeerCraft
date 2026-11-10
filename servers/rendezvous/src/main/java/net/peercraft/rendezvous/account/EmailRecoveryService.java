package net.peercraft.rendezvous.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

/** Mailbox proof only changes credentials; it never allocates or changes a player UUID.
 * Callers must use authenticated HTTPS, never the plaintext rendezvous UDP protocol. */
public final class EmailRecoveryService {
    public record Mail(String recipient, String subject, String body) { }
    /** Must enqueue without blocking. False means the bounded queue is unavailable. */
    public interface MailSender { boolean enqueue(Mail mail); }
    public record Challenge(String requestId, long expiresInSeconds) { }
    public record Profile(String email, String salt) { }
    public record RecoveredAccount(UUID accountId, String friendCode) { }
    public static final class Failure extends RuntimeException {
        public final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final long TTL = 15 * 60_000L;
    static final int MAX_PENDING = 10_000;
    private enum Purpose { BIND, RESET }
    private static final class Attempt {
        final Purpose purpose; final UUID accountId; final String email; final byte[] digest;
        final long expires; int guesses = 5;
        Attempt(Purpose purpose, UUID accountId, String email, byte[] digest, long expires) {
            this.purpose = purpose; this.accountId = accountId; this.email = email;
            this.digest = digest; this.expires = expires;
        }
    }
    private record Bucket(long until, int count) { }
    private final AccountService accounts;
    private final AccountStore store;
    private final SessionRegistry sessions;
    private final PendingAuthRegistry pendingAuth;
    private final MailSender sender;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<String, Bucket> limits = new HashMap<>();

    EmailRecoveryService(AccountService accounts, AccountStore store, SessionRegistry sessions,
                         PendingAuthRegistry pendingAuth, MailSender sender, LongSupplier clock) {
        this.accounts = accounts; this.store = store; this.sessions = sessions;
        this.pendingAuth = pendingAuth; this.sender = Objects.requireNonNull(sender); this.clock = clock;
    }

    public synchronized String verifiedEmail(byte[] sessionToken) {
        return authorized(sessionToken).verifiedEmail;
    }

    public synchronized Profile profile(byte[] sessionToken) {
        synchronized (accounts) {
            Account account = authorized(sessionToken);
            return new Profile(account.verifiedEmail, Base64.getEncoder().encodeToString(account.passwordSalt));
        }
    }

    /** Password proof accompanies the session so a stolen remembered session cannot bind mail. */
    public synchronized Challenge beginBinding(byte[] sessionToken, byte[] currentPasswordHash,
                                               String address, String sourceIp) {
        synchronized (accounts) {
            Account account = authorized(sessionToken);
            if (currentPasswordHash == null || !MessageDigest.isEqual(account.passwordHash, currentPasswordHash))
                throw new Failure("bad_credentials");
            String email = normalize(address);
            limit("account:" + account.accountId, 3, TTL);
            throttle(email, sourceIp);
            if (owner(email).filter(a -> !a.accountId.equals(account.accountId)).isPresent())
                throw new Failure("email_unavailable");
            return begin(Purpose.BIND, account.accountId, email, true);
        }
    }

    public synchronized void confirmBinding(byte[] sessionToken, String requestId, String code) {
        synchronized (accounts) {
            // Keep the periodic saver from observing credentials before persistence succeeds.
            synchronized (store) {
                Account account = authorized(sessionToken);
                Attempt attempt = check(Purpose.BIND, requestId, code, account.accountId);
                if (owner(attempt.email).filter(a -> !a.accountId.equals(account.accountId)).isPresent())
                    throw new Failure("email_unavailable");
                String previous = account.verifiedEmail;
                account.verifiedEmail = attempt.email;
                try { store.saveNow(); }
                catch (IOException | RuntimeException failure) {
                    account.verifiedEmail = previous;
                    throw new Failure("storage_unavailable");
                }
                invalidate(account.accountId);
                if (previous != null && !previous.equals(attempt.email))
                    sender.enqueue(new Mail(previous, "PeerCraft: recovery email changed",
                            "The recovery email for your PeerCraft account has changed. Account ID: " + account.accountId));
            }
        }
    }

    /** Known and unknown addresses receive the same response and a random request ID. */
    public synchronized Challenge beginReset(String address, String sourceIp) {
        String email = normalize(address);
        throttle(email, sourceIp);
        Account account = owner(email).orElse(null);
        return begin(Purpose.RESET, account == null ? null : account.accountId, email, account != null);
    }

    public synchronized RecoveredAccount finishReset(String requestId, String code, byte[] salt, byte[] passwordHash) {
        if (salt == null || salt.length != 16 || passwordHash == null || passwordHash.length != 32)
            throw new Failure("invalid_password_data");
        synchronized (accounts) {
            // Keep the periodic saver from observing credentials before persistence succeeds.
            synchronized (store) {
                Attempt attempt = check(Purpose.RESET, requestId, code, null);
                Account account = store.byId(attempt.accountId).orElseThrow(() -> new Failure("invalid_code"));
                if (account.licensed || !attempt.email.equals(account.verifiedEmail)) throw new Failure("invalid_code");
                byte[] oldSalt = account.passwordSalt, oldHash = account.passwordHash, oldRemember = account.rememberToken;
                account.passwordSalt = salt.clone(); account.passwordHash = passwordHash.clone(); account.rememberToken = null;
                try { store.saveNow(); }
                catch (IOException | RuntimeException failure) {
                    account.passwordSalt = oldSalt; account.passwordHash = oldHash; account.rememberToken = oldRemember;
                    throw new Failure("storage_unavailable");
                }
                sessions.invalidateAccount(account.accountId);
                pendingAuth.invalidateAccount(account.accountId);
                invalidate(account.accountId);
                sender.enqueue(new Mail(account.verifiedEmail, "PeerCraft: password changed",
                        "Your PeerCraft password was changed. Your account ID and world progress identity are unchanged.\n"
                                + "Account ID: " + account.accountId));
                return new RecoveredAccount(account.accountId, account.friendCode);
            }
        }
    }

    public synchronized void maintenance() {
        long now = clock.getAsLong();
        attempts.values().removeIf(a -> a.expires <= now);
        limits.values().removeIf(b -> b.until <= now);
    }

    private Account authorized(byte[] token) {
        if (token == null || token.length != 16) throw new Failure("unauthorized");
        UUID id = sessions.validate(token).orElseThrow(() -> new Failure("unauthorized"));
        Account account = store.byId(id).orElseThrow(() -> new Failure("unauthorized"));
        if (account.licensed) throw new Failure("unlicensed_only");
        return account;
    }
    private Optional<Account> owner(String email) {
        return store.all().stream().filter(a -> !a.licensed && email.equals(a.verifiedEmail)).findFirst();
    }
    private Challenge begin(Purpose purpose, UUID account, String email, boolean deliver) {
        maintenance();
        if (attempts.size() >= MAX_PENDING) throw new Failure("rate_limited");
        byte[] nonce = new byte[16]; random.nextBytes(nonce);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        Attempt attempt = new Attempt(purpose, account, email, digest(id, code), clock.getAsLong() + TTL);
        // Unknown addresses only hold a dummy challenge until expiry.
        if (deliver && !sender.enqueue(new Mail(email, purpose == Purpose.BIND
                ? "PeerCraft: confirm email / Подтверждение почты" : "PeerCraft: recover account / Восстановление аккаунта",
                "Code / Код: " + code + "\nValid for 15 minutes / Действует 15 минут.\n"
                        + "If you did not request this, ignore the email. / Если вы не запрашивали код, игнорируйте письмо."))) {
            // Keep the public response identical. A missing letter can be retried after cooldown.
            deliver = false;
        }
        if (!deliver) attempt = new Attempt(purpose, null, email, digest(id, code), clock.getAsLong() + TTL);
        attempts.put(id, attempt);
        return new Challenge(id, TTL / 1000);
    }
    private Attempt check(Purpose purpose, String id, String code, UUID account) {
        Attempt attempt = attempts.get(id);
        if (attempt == null || attempt.expires <= clock.getAsLong() || attempt.purpose != purpose)
            throw new Failure("invalid_code");
        if (account != null && !account.equals(attempt.accountId)) throw new Failure("invalid_code");
        boolean matches = code != null && code.matches("[0-9]{8}")
                && MessageDigest.isEqual(attempt.digest, digest(id, code));
        if (--attempt.guesses <= 0 || matches) attempts.remove(id);
        if (!matches || attempt.accountId == null) throw new Failure("invalid_code");
        return attempt;
    }
    private void invalidate(UUID account) {
        attempts.values().removeIf(a -> account.equals(a.accountId));
    }
    private void throttle(String email, String ip) {
        maintenance();
        limit("global", 120, 60_000);
        limit("ip:" + Objects.requireNonNull(ip), 5, TTL);
        limit("mail:" + email, 3, TTL);
    }
    private void limit(String key, int maximum, long window) {
        Bucket bucket = limits.get(key); long now = clock.getAsLong();
        if (bucket != null && bucket.until > now && bucket.count >= maximum) throw new Failure("rate_limited");
        if (bucket == null || bucket.until <= now) {
            if (limits.size() >= MAX_PENDING * 3) throw new Failure("rate_limited");
            limits.put(key, new Bucket(now + window, 1));
        } else limits.put(key, new Bucket(bucket.until, bucket.count + 1));
    }
    static String normalize(String address) {
        if (address == null) throw new Failure("invalid_email");
        String email = address.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"))
            throw new Failure("invalid_email");
        return email;
    }
    private static byte[] digest(String id, String code) {
        try { return MessageDigest.getInstance("SHA-256").digest((id + ":" + code).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
