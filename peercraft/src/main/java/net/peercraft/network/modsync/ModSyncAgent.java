package net.peercraft.network.modsync;

/**
 * The joiner-side mod-sync hook {@code P2PBridge} invokes after a successful NAT punch and
 * before {@code ConnectListener.onConnected()} would fire (see
 * {@code P2PBridge.beginClientPunch}). The implementation ({@code ClientModSyncAgent}, in
 * the client layer) runs the handshake over {@code link}, optionally shows screens, and then
 * calls exactly one {@link Outcome} method.
 *
 * <p>{@link #run} is called on a background thread and must not block it — kick off work and
 * return; the Outcome call comes later, also off-thread.
 */
public interface ModSyncAgent {

    void run(ModSyncLink link, Outcome outcome);

    interface Outcome {
        /** Nothing to sync (or mod-sync disabled / host doesn't participate) — continue the join normally. */
        void proceedToConnect();

        /** Mods were downloaded (restart needed) or the player cancelled — do NOT connect, release the join lock. */
        void abortJoin();

        /** Something went wrong — surface {@code reasonKey} through the existing ConnectListener failure path. */
        void fail(String reasonKey);
    }
}
