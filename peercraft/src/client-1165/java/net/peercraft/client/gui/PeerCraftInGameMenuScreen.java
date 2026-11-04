package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 1.16.5 twin of the in-world PeerCraft hub behind the pause menu's small "PC" button. */
public class PeerCraftInGameMenuScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private boolean refreshStarted;

    private static final class Entry {
        final Component label; final boolean primary; final Button.OnPress onPress;
        Entry(Component label, boolean primary, Button.OnPress onPress) { this.label = label; this.primary = primary; this.onPress = onPress; }
    }

    public PeerCraftInGameMenuScreen(Screen parent) {
        super(new TranslatableComponent("peercraft.ingame.title"), 300);
        this.parent = parent;
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        if (AccountClient.INSTANCE.getCurrentSession() != null) {
            int pending = FriendRequestNotifier.pendingCount();
            entries.add(new Entry(pending > 0
                    ? new TranslatableComponent("peercraft.ingame.friends_pending", pending)
                    : new TranslatableComponent("peercraft.ingame.friends"), pending > 0,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftInGameFriendsScreen(this))));
            entries.add(new Entry(new TranslatableComponent("peercraft.ingame.players"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftServerPlayersScreen(this))));
        } else {
            entries.add(new Entry(new TranslatableComponent("peercraft.ingame.sign_in"), true,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(this))));
        }
        entries.add(new Entry(new TranslatableComponent("peercraft.ingame.session"), false,
                b -> PeerCraftUi.setScreen(minecraft, new PeerCraftSessionScreen(this))));
        // Host-only tools: a joiner has nothing to hand off or admit.
        if (minecraft != null && minecraft.hasSingleplayerServer()) {
            if (PeerCraftConfig.handoff()) entries.add(new Entry(new TranslatableComponent("peercraft.handoff.menu.button"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new HandoffPlayerPickerScreen(this))));
            entries.add(new Entry(new TranslatableComponent("peercraft.admission.menu"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new JoinRequestsScreen(this))));
        }
        entries.add(new Entry(new TranslatableComponent("peercraft.gui.common.back"), false, b -> onClose()));
        return entries;
    }

    @Override
    protected void init() {
        setFocused(null);
        buttons.clear(); children.clear();
        super.init();
        if (!refreshStarted && AccountClient.INSTANCE.getCurrentSession() != null) {
            refreshStarted = true;
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override public void onResult(List<AccountClient.IncomingRequest> requests) {
                    FriendRequestNotifier.update(requests);
                    minecraft.execute(() -> { if (PeerCraftUi.isCurrentScreen(PeerCraftInGameMenuScreen.this)) init(); });
                }
                @Override public void onTimeout() {
                }
            });
        }
        List<Entry> entries = entries();
        int desired = (this.height < 300 ? 36 : 46) + 6 + 2 * 12 + 14 + entries.size() * (this.height < 300 ? 22 : 30);
        this.dialog = new SteampunkDialog(this.width, this.height, desired, this.title);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            dialogAction(entry.label, entry.onPress, entry.primary, i, entries.size());
        }
    }

    private List<String> body() {
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        return Collections.singletonList(session != null
                ? new TranslatableComponent("peercraft.ingame.signed_in_as", session.displayName()).getString()
                : new TranslatableComponent("peercraft.ingame.sign_in_needed").getString());
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.parent);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        drawBody(pose, body(), PeerCraftUi.TEXT_MUTED);
        super.render(pose, mouseX, mouseY, partialTick);
    }
}
