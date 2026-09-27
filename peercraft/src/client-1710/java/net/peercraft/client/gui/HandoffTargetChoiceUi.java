package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiYesNo;
import net.peercraft.client.handoff.SuccessorLauncher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Native dialogs collect target and backup choices during preflight. */
public final class HandoffTargetChoiceUi implements SuccessorLauncher.SelectionUi {
    public CompletableFuture<SuccessorLauncher.TargetChoice> choose(List<Path> copies) {
        CompletableFuture<SuccessorLauncher.TargetChoice> result = new CompletableFuture<>();
        show(copies, 0, result); return result;
    }
    private void show(List<Path> copies, int index, CompletableFuture<SuccessorLauncher.TargetChoice> result) {
        if (result.isDone()) return;
        if (index >= copies.size()) { result.completeExceptionally(new IOException("No return copy selected")); return; }
        Path copy = copies.get(index); String name = copy.getFileName().toString();
        if (copies.size() == 1) { policy(copy, result); return; }
        PeerCraftUi.setScreen(Minecraft.getMinecraft(), new GuiYesNo(
                (accepted, ignoredId) -> {
                    if (accepted) policy(copy, result); else show(copies, index + 1, result);
                },
                PeerCraftLang.tr("peercraft.handoff.target.title"),
                PeerCraftLang.tr("peercraft.handoff.target.body", name),
                PeerCraftLang.tr("peercraft.handoff.target.use"),
                PeerCraftLang.tr("peercraft.handoff.target.next"), 0));
    }
    private void policy(Path copy, CompletableFuture<SuccessorLauncher.TargetChoice> result) {
        if (result.isDone()) return;
        String name = copy.getFileName().toString();
        String backup = net.peercraft.client.handoff.WorldTargetPlan.backupName(copy, true);
        PeerCraftUi.setScreen(Minecraft.getMinecraft(), new HandoffReclaimConfirmScreen(name, backup,
                () -> result.complete(new SuccessorLauncher.TargetChoice(copy, true, backup)),
                () -> result.complete(new SuccessorLauncher.TargetChoice(copy, false))));
    }
}
