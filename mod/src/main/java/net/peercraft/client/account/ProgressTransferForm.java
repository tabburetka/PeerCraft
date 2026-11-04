package net.peercraft.client.account;

import net.peercraft.network.account.AccountClient;
import net.peercraft.world.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Host choices and verified destination lookup, shared by the native GUI adapters. */
public final class ProgressTransferForm {
    public enum Stage { WORLDS, PLAYERS, ACCOUNT, COMPLETE }
    public Stage stage = Stage.WORLDS;
    public List<PlayerProgressCatalog.World> worlds = Collections.emptyList();
    public List<PlayerProgressCatalog.Player> players = Collections.emptyList();
    public PlayerProgressCatalog.World world;
    public PlayerProgressCatalog.Player player;
    public UUID target;
    public String targetName = "", friendCode = "", status = "peercraft.gui.transfer.intro";
    public boolean busy;
    public Path backup;
    private int generation;
    private AccountClient.AccountSession approvedBy;
    private static final ExecutorService WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> { Thread t = new Thread(task, "peercraft-progress-transfer"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    public void loadWorlds(Path saves, Consumer<Runnable> scheduler, Runnable refresh) {
        stage = Stage.WORLDS; status = "peercraft.gui.transfer.loading";
        run(() -> PlayerProgressCatalog.worlds(saves), result -> {
            worlds = result; status = worlds.isEmpty() ? "peercraft.gui.transfer.no_worlds" : "peercraft.gui.transfer.select_world";
        }, scheduler, refresh);
    }
    public void chooseWorld(PlayerProgressCatalog.World choice, Consumer<Runnable> scheduler, Runnable refresh) {
        world = choice; stage = Stage.PLAYERS; status = "peercraft.gui.transfer.loading";
        run(() -> PlayerProgressCatalog.unassignedPlayers(choice.directory), result -> {
            players = result; status = players.isEmpty() ? "peercraft.gui.transfer.no_players" : "peercraft.gui.transfer.select_player";
        }, scheduler, refresh);
    }
    public void choosePlayer(PlayerProgressCatalog.Player choice) {
        player = choice; stage = Stage.ACCOUNT; status = "peercraft.gui.transfer.enter_account"; target = null;
    }
    public void lookup(String code, Consumer<Runnable> scheduler, Runnable refresh, Runnable confirm) {
        if (busy) return;
        AccountClient.AccountSession current = AccountSessionHolder.current();
        if (current == null) { status = "peercraft.gui.transfer.account_required"; refresh.run(); return; }
        code = code.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{6}")) { status = "peercraft.gui.transfer.invalid_code"; refresh.run(); return; }
        approvedBy = current; friendCode = code; busy = true; status = "peercraft.gui.transfer.checking";
        final int request = ++generation;
        AccountClient.INSTANCE.lookupFriendCode(code, new AccountClient.FriendCodeLookupCallback() {
            @Override public void onResult(boolean found, UUID id, boolean licensed, String name) {
                scheduler.accept(() -> {
                    if (request != generation) return;
                    busy = false;
                    if (!found) { status = "peercraft.gui.transfer.unknown_account"; refresh.run(); }
                    else { target = id; targetName = name; confirm.run(); }
                });
            }
            @Override public void onTimeout() {
                scheduler.accept(() -> { if (request != generation) return;
                    busy = false; status = "peercraft.gui.transfer.account_unavailable"; refresh.run(); });
            }
        });
        refresh.run();
    }
    public void transfer(boolean worldOpen, Consumer<Runnable> scheduler, Runnable refresh) {
        if (busy) return;
        AccountClient.AccountSession current = AccountSessionHolder.current();
        if (worldOpen || current == null || approvedBy == null || !current.accountId().equals(approvedBy.accountId())
                || !Arrays.equals(current.sessionToken(), approvedBy.sessionToken()) || target == null || world == null || player == null) {
            status = worldOpen ? "peercraft.gui.transfer.close_world" : "peercraft.gui.transfer.account_required"; refresh.run(); return;
        }
        status = "peercraft.gui.transfer.moving";
        final Path worldPath = world.directory; final UUID source = player.id, destination = target;
        run(() -> PlayerDataMigration.assignGuestInStoppedWorld(worldPath, source, destination), result -> {
            backup = result; stage = Stage.COMPLETE; status = "peercraft.gui.transfer.complete";
        }, scheduler, refresh);
    }
    public void cancelLookup() { if (stage == Stage.ACCOUNT) { generation++; busy = false; } }
    private <T> void run(Callable<T> work, Consumer<T> success, Consumer<Runnable> scheduler, Runnable refresh) {
        busy = true; final int request = ++generation; refresh.run();
        try { WORK.execute(() -> {
            T result = null; Throwable error = null;
            try { result = work.call(); } catch (Exception failure) { error = failure; }
            final T value = result; final Throwable failure = error;
            scheduler.accept(() -> {
                if (request != generation) return;
                busy = false;
                if (failure == null) success.accept(value);
                else status = "peercraft.gui.transfer.failed";
                refresh.run();
            });
        }); } catch (RejectedExecutionException full) { busy = false; status = "peercraft.gui.transfer.busy"; refresh.run(); }
    }
}
