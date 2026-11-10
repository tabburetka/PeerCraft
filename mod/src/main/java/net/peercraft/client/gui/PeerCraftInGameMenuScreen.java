package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;

import java.util.ArrayList;
import java.util.List;

/** The in-world PeerCraft hub behind the pause menu's small "PC" button. */
public class PeerCraftInGameMenuScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private boolean refreshStarted;

    private record Entry(Component label, boolean primary, Button.OnPress onPress) {
    }

    public PeerCraftInGameMenuScreen(Screen parent) {
        super(Component.translatable("peercraft.ingame.title"));
        this.parent = parent;
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        if (AccountClient.INSTANCE.getCurrentSession() != null) {
            int pending = FriendRequestNotifier.pendingCount();
            entries.add(new Entry(pending > 0
                    ? Component.translatable("peercraft.ingame.friends_pending", pending)
                    : Component.translatable("peercraft.ingame.friends"), pending > 0,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftInGameFriendsScreen(this))));
            entries.add(new Entry(Component.translatable("peercraft.ingame.players"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftServerPlayersScreen(this))));
        } else {
            entries.add(new Entry(Component.translatable("peercraft.ingame.sign_in"), true,
                    b -> PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(this))));
        }
        entries.add(new Entry(Component.translatable("peercraft.ingame.session"), false,
                b -> PeerCraftUi.setScreen(minecraft, new PeerCraftSessionScreen(this))));
        // Host-only tools: a joiner has nothing to hand off or admit.
        if (minecraft != null && minecraft.hasSingleplayerServer()) {
            if (PeerCraftConfig.handoff()) entries.add(new Entry(Component.translatable("peercraft.handoff.menu.button"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new HandoffPlayerPickerScreen(this))));
            entries.add(new Entry(Component.translatable("peercraft.admission.menu"), false,
                    b -> PeerCraftUi.setScreen(minecraft, new JoinRequestsScreen(this))));
        }
        entries.add(new Entry(Component.translatable("peercraft.gui.common.back"), false, b -> onClose()));
        return entries;
    }

    @Override
    protected void init() {
        super.init();
        if (!refreshStarted && AccountClient.INSTANCE.getCurrentSession() != null) {
            refreshStarted = true;
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override public void onResult(List<AccountClient.IncomingRequest> requests) {
                    FriendRequestNotifier.update(requests);
                    minecraft.execute(() -> { if (PeerCraftUi.isCurrentScreen(PeerCraftInGameMenuScreen.this)) rebuildWidgets(); });
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
            addRenderableWidget(dialogAction(entry.label(), entry.onPress(), entry.primary(), i, entries.size()));
        }
    }

    private List<String> body() {
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        return List.of(session != null
                ? Component.translatable("peercraft.ingame.signed_in_as", session.displayName()).getString()
                : Component.translatable("peercraft.ingame.sign_in_needed").getString());
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.parent);
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, body(), SteampunkSettingsTheme.MUTED);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, body(), SteampunkSettingsTheme.MUTED);
    }*/
    //?}
}
