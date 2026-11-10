package net.peercraft.client.gui;
public final class AdmissionScreens {
    private AdmissionScreens() { }
    public static void join(net.minecraft.client.gui.GuiScreen owner,String room,Runnable proceed) {
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession()==null){proceed.run();return;}
        //? if >=1.12 {
        if(!client.waiting())client.request(room,()->net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(()->{
        //?} else {
//~         if(!client.waiting())client.request(room,()->net.minecraft.client.Minecraft.getMinecraft().func_152344_a(()->{
        //?}
            PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getMinecraft(),owner);proceed.run();
        }));
        PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getMinecraft(),new JoinRequestWaitingScreen(owner));
    }

    private static final java.util.List<java.lang.ref.WeakReference<net.minecraft.client.gui.GuiButton>> watched=new java.util.ArrayList<>();
    public static net.minecraft.client.gui.GuiButton watch(net.minecraft.client.gui.GuiButton button){watched.add(new java.lang.ref.WeakReference<>(button));return button;}
    public static void tick(){
        net.minecraft.client.gui.GuiScreen current=net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        if(current==null)return;
        String base=PeerCraftLang.tr("peercraft.admission.menu"),shortLabel=PeerCraftLang.tr("peercraft.admission.menu_short");
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        for(java.util.Iterator<java.lang.ref.WeakReference<net.minecraft.client.gui.GuiButton>> it=watched.iterator();it.hasNext();){
            net.minecraft.client.gui.GuiButton button=it.next().get();if(button==null){it.remove();continue;}String label=button.displayString;
            if(label.startsWith(base))button.displayString=base+" · "+client.entries().size();
            if(label.startsWith(shortLabel))button.displayString=shortLabel+(client.waiting()?" · "+PeerCraftLang.tr(client.guestStatus==2?"peercraft.admission.ready_short":"peercraft.admission.pending_short"):"");
        }
    }
}
