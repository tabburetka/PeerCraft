package net.peercraft.client;

/**
 * Marks a screen that belongs to the join attempt in progress (mod sync review, download,
 * security notice). TransportNoticeController keeps the attempt alive while one of these is
 * open instead of treating it as the player leaving the join; new screens in the flow only
 * need to implement this, not be added to an instanceof list.
 */
public interface JoinFlowScreen {
}
