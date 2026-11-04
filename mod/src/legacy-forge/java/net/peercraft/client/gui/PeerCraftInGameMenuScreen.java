package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;

//? if >=1.12 {
import java.io.IOException;
//?}
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//? if >=1.12 {
/** Forge 1.12.2 twin of the in-world PeerCraft hub behind the pause menu's small "PC" button. */
//?} else {
//~ /** Forge 1.7.10 twin of the in-world PeerCraft hub behind the pause menu's small "PC" button. */
//?}
public class PeerCraftInGameMenuScreen extends PeerCraftDialogScreen {
    private final GuiScreen parent;
    private boolean refreshStarted;

    private static final class Entry {
        final String label; final boolean primary; final Runnable onPress;
        Entry(String label, boolean primary, Runnable onPress) { this.label = label; this.primary = primary; this.onPress = onPress; }
    }

    public PeerCraftInGameMenuScreen(GuiScreen parent) {
        super(PeerCraftLang.tr("peercraft.ingame.title"), 300);
        this.parent = parent;
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        if (AccountClient.INSTANCE.getCurrentSession() != null) {
            int pending = FriendRequestNotifier.pendingCount();
            entries.add(new Entry(pending > 0
                    ? PeerCraftLang.tr("peercraft.ingame.friends_pending", pending)
                    : PeerCraftLang.tr("peercraft.ingame.friends"), pending > 0,
                    () -> PeerCraftUi.setScreen(mc, new PeerCraftInGameFriendsScreen(this))));
            entries.add(new Entry(PeerCraftLang.tr("peercraft.ingame.players"), false,
                    () -> PeerCraftUi.setScreen(mc, new PeerCraftServerPlayersScreen(this))));
        } else {
            entries.add(new Entry(PeerCraftLang.tr("peercraft.ingame.sign_in"), true,
                    () -> PeerCraftUi.setScreen(mc, new PeerCraftAccountScreen(this))));
        }
        entries.add(new Entry(PeerCraftLang.tr("peercraft.ingame.session"), false,
                () -> PeerCraftUi.setScreen(mc, new PeerCraftSessionScreen(this))));
        // Host-only tools: a joiner has nothing to hand off or admit.
        if (mc != null && mc.isIntegratedServerRunning()) {
            if (PeerCraftConfig.handoff()) entries.add(new Entry(PeerCraftLang.tr("peercraft.handoff.menu.button"), false,
                    () -> PeerCraftUi.setScreen(mc, new HandoffPlayerPickerScreen(this))));
            entries.add(new Entry(PeerCraftLang.tr("peercraft.admission.menu"), false,
                    () -> PeerCraftUi.setScreen(mc, new JoinRequestsScreen(this))));
        }
        entries.add(new Entry(PeerCraftLang.tr("peercraft.gui.common.back"), false, this::close));
        return entries;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        if (!refreshStarted && AccountClient.INSTANCE.getCurrentSession() != null) {
            refreshStarted = true;
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override public void onResult(List<AccountClient.IncomingRequest> requests) {
                    FriendRequestNotifier.update(requests);
                    //? if >=1.12 {
                    mc.addScheduledTask(() -> { if (PeerCraftUi.isCurrentScreen(PeerCraftInGameMenuScreen.this)) initGui(); });
                    //?} else {
//~                     mc.func_152344_a(() -> { if (PeerCraftUi.isCurrentScreen(PeerCraftInGameMenuScreen.this)) initGui(); });
                    //?}
                }
                @Override public void onTimeout() {
                }
            });
        }
        List<Entry> entries = entries();
        int desired = (this.height < 300 ? 36 : 46) + 6 + 2 * 12 + 14 + entries.size() * (this.height < 300 ? 22 : 30);
        this.dialog = new SteampunkDialog(this.width, this.height, desired, PeerCraftLang.tr("peercraft.ingame.title"));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            IdButton button = dialogAction(entry.label, entry.onPress, entry.primary, i, entries.size());
            if (entry.label.startsWith(PeerCraftLang.tr("peercraft.admission.menu"))) AdmissionScreens.watch(button);
        }
    }

    private void close() {
        PeerCraftUi.setScreen(this.mc, this.parent);
    }

    @Override
    //? if >=1.12 {
    protected void actionPerformed(GuiButton button) throws IOException {
    //?} else {
//~     protected void actionPerformed(GuiButton button) {
    //?}
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }

    @Override
    //? if >=1.12 {
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
    //?} else {
//~     protected void keyTyped(char typedChar, int keyCode) {
    //?}
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) { close(); return; }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        drawBody(Collections.singletonList(session != null
                ? PeerCraftLang.tr("peercraft.ingame.signed_in_as", session.displayName())
                : PeerCraftLang.tr("peercraft.ingame.sign_in_needed")), PeerCraftUi.TEXT_MUTED);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
