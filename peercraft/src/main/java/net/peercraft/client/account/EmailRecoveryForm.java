package net.peercraft.client.account;

import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Shared flow for modern and legacy UI adapters; all form state changes run on the client thread. */
public final class EmailRecoveryForm {
    public enum Stage { ADDRESS, CODE, COMPLETE }
    public final boolean binding;
    public Stage stage = Stage.ADDRESS;
    public String status = "peercraft.gui.email.intro";
    public boolean busy;
    public String email = "";
    public EmailRecoveryClient.RecoveredAccount restored;
    private String requestId;
    private final AccountClient.AccountSession original;
    private final EmailRecoveryClient client = new EmailRecoveryClient(PeerCraftConfig.emailServiceUrl());
    public EmailRecoveryForm(boolean binding) { this.binding = binding; this.original = AccountSessionHolder.current(); }

    public void begin(String address, char[] password, Consumer<Runnable> scheduler, Runnable refresh) {
        if (busy) { Arrays.fill(password, '\0'); return; }
        email = address.trim();
        if (email.isEmpty() || !email.contains("@")) { fail("invalid_email", password, refresh); return; }
        if (binding && (!sameSession() || password.length == 0)) {
            fail(sameSession() ? "password_required" : "unauthorized", password, refresh); return;
        }
        busy = true; status = "peercraft.gui.email.sending";
        CompletableFuture<EmailRecoveryClient.Challenge> operation;
        if (binding) operation = client.beginBinding(original.sessionToken(), email, password);
        else { Arrays.fill(password, '\0'); operation = client.beginReset(email); }
        refresh.run();
        operation.whenComplete((challenge, failure) -> scheduler.accept(() -> {
            busy = false;
            if (failure != null) status = "peercraft.gui.email.error." + EmailRecoveryClient.errorCode(failure);
            else { requestId = challenge.requestId; stage = Stage.CODE; status = "peercraft.gui.email.code_sent"; }
            refresh.run();
        }));
    }
    public void confirm(String code, char[] password, char[] repeat, Consumer<Runnable> scheduler, Runnable refresh) {
        if (busy) { Arrays.fill(password, '\0'); Arrays.fill(repeat, '\0'); return; }
        if (!code.matches("[0-9]{8}")) { Arrays.fill(repeat, '\0'); fail("invalid_code", password, refresh); return; }
        if (binding && !sameSession()) { Arrays.fill(repeat, '\0'); fail("unauthorized", password, refresh); return; }
        if (!binding && (password.length < 8 || password.length > 64 || !Arrays.equals(password, repeat))) {
            boolean match = Arrays.equals(password, repeat); Arrays.fill(repeat, '\0');
            fail(match ? "password_length" : "password_mismatch", password, refresh); return;
        }
        Arrays.fill(repeat, '\0'); busy = true; status = "peercraft.gui.email.checking";
        if (binding) {
            Arrays.fill(password, '\0');
            client.confirmBinding(original.sessionToken(), requestId, code).whenComplete((result, failure) -> scheduler.accept(() -> {
                finish(failure, refresh);
            }));
        } else client.finishReset(requestId, code, password).whenComplete((result, failure) -> {
            if (result != null) AccountStorage.saveRecoveryCard(new AccountState(result.accountId, false, result.friendCode, "", null));
            scheduler.accept(() -> {
                if (result != null) {
                    restored = result;
                    AccountClient.AccountSession current = AccountSessionHolder.current();
                    if (current != null && current.accountId().equals(result.accountId)) AccountSessionHolder.logout();
                }
                finish(failure, refresh);
            });
        });
        refresh.run();
    }
    public void restart() {
        if (busy) return;
        stage = Stage.ADDRESS; requestId = null; status = "peercraft.gui.email.intro";
    }
    private void finish(Throwable failure, Runnable refresh) {
        busy = false;
        if (failure != null) status = "peercraft.gui.email.error." + EmailRecoveryClient.errorCode(failure);
        else { stage = Stage.COMPLETE; status = binding ? "peercraft.gui.email.bound" : "peercraft.gui.email.recovered"; }
        refresh.run();
    }
    private boolean sameSession() {
        AccountClient.AccountSession current = AccountSessionHolder.current();
        return original != null && current != null && !current.licensed() && current.accountId().equals(original.accountId())
                && Arrays.equals(current.sessionToken(), original.sessionToken());
    }
    private void fail(String reason, char[] password, Runnable refresh) {
        Arrays.fill(password, '\0'); status = "peercraft.gui.email.error." + reason; refresh.run();
    }
}
