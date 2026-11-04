package net.peercraft.client.gui;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
public class JoinRequestsScreen extends net.minecraft.client.gui.screens.Screen {

    private final net.minecraft.client.gui.screens.Screen parent;
    private int page;
    private final boolean guestView;
    private int offset;
    private String signature="";
    private final net.peercraft.network.admission.AdmissionClient client = net.peercraft.network.admission.AdmissionClient.INSTANCE;
    public JoinRequestsScreen(net.minecraft.client.gui.screens.Screen parent) { this(parent,false); }
    protected JoinRequestsScreen(net.minecraft.client.gui.screens.Screen parent, boolean guest) { super(net.minecraft.network.chat.Component.translatable(guest?"peercraft.admission.waiting_title":"peercraft.admission.title")); this.parent=parent;this.guestView=guest;this.page=guest?1:0; }
    private int panelWidth(){return Math.min(440,width-16);}
    private int panelHeight(){return Math.min(350,height-16);}
    private int left(){return (width-panelWidth())/2;}
    private int top(){return (height-panelHeight())/2;}
    private String tr(String key,Object... args){return net.minecraft.network.chat.Component.translatable("peercraft.admission."+key,args).getString();}
    private void change(int next){if(guestView || next==1)return;page=next;offset=0;refresh();}
    private void controls(){
        if(!guestView){
            int tab=(panelWidth()-28)/2;
            action(12,44,tab,tr("host"),()->change(0),page==0);
            action(16+tab,44,tab,tr("settings"),()->change(2),page==2);
        }
        if(page==0){
            java.util.List<net.peercraft.network.admission.AdmissionClient.Entry> entries=client.entries();
            offset=Math.max(0,Math.min(offset,Math.max(0,entries.size()-1)));
            int rows=Math.max(1,Math.min(2,(panelHeight()-170)/70));
            for(int i=offset;i<Math.min(entries.size(),offset+rows);i++){
                final net.peercraft.network.admission.AdmissionClient.Entry e=entries.get(i);int y=135+(i-offset)*70;int bw=(panelWidth()-48)/3;
                action(18,y,bw,tr("allow"),()->client.decide(e.id,2),true);
                action(24+bw,y,bw,tr("deny"),()->client.decide(e.id,3),false);
                action(30+2*bw,y,bw,tr("block"),()->client.decide(e.id,4),false);
            }
            if(entries.size()>rows){action(12,panelHeight()-58,(panelWidth()-30)/2,tr("previous"),()->{offset=Math.max(0,offset-rows);refresh();},false);action(18+(panelWidth()-30)/2,panelHeight()-58,(panelWidth()-30)/2,tr("next"),()->{offset+=rows;refresh();},false);}
        }else if(page==1){
            boolean compact=panelHeight()<300;
            if(client.guestStatus==2){
                net.minecraft.client.gui.components.Button enter=action(18,compact?panelHeight()-58:155,compact?(panelWidth()-42)/2:panelWidth()-36,tr(minecraft.level!=null?"leave_world":"enter"),()->client.confirm(),true);
                enter.active=minecraft.level==null;
            }
            if(client.waiting())action(compact&&client.guestStatus==2?24+(panelWidth()-42)/2:18,compact?panelHeight()-58:181,compact&&client.guestStatus==2?(panelWidth()-42)/2:panelWidth()-36,tr("cancel"),()->{client.cancel();refresh();},false);
            if(!compact)action(18,213,panelWidth()-36,tr("games"),()->PeerCraftUi.setScreen(minecraft,new PeerCraftMultiplayerScreen(parent)),false);
        }else{
            action(18,100,panelWidth()-36,tr(net.peercraft.config.PeerCraftConfig.approveStrangers()?"protection_on":"protection_off"),()->{
                net.peercraft.config.PeerCraftSettings s=net.peercraft.config.PeerCraftSettingsStore.load();
                s.set("privacy.approveStrangers",Boolean.toString(!net.peercraft.config.PeerCraftConfig.approveStrangers()));
                net.peercraft.config.PeerCraftSettingsStore.save(s);net.peercraft.config.PeerCraftConfig.applyOverrides(s.toOverrideMap());refresh();
            },net.peercraft.config.PeerCraftConfig.approveStrangers());
        }
        action(12,panelHeight()-30,panelWidth()-24,tr("done"),this::onClose,true);
    }
    //? if <26.1 {
    private void drawContent(GuiGraphics g){
        frame(g,left(),top(),panelWidth(),panelHeight());
        text(g,tr(guestView?"waiting_title":"title"),12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
        text(g,guestView?tr("room",client.guestRoom):tr("subtitle"),28,net.peercraft.client.theme.SteampunkPalette.MUTED);
        if(page==0){
            text(g,tr("disclosure"),76,net.peercraft.client.theme.SteampunkPalette.TEXT);
            java.util.List<net.peercraft.network.admission.AdmissionClient.Entry> entries=client.entries();
            int rows=Math.max(1,Math.min(2,(panelHeight()-170)/70));
            for(int i=offset;i<Math.min(entries.size(),offset+rows);i++){
                net.peercraft.network.admission.AdmissionClient.Entry e=entries.get(i);
                text(g,tr("request",e.name,Math.max(0,(e.expires-System.currentTimeMillis()+999)/1000)),116+(i-offset)*70,net.peercraft.client.theme.SteampunkPalette.ACCENT);
            }
            if(entries.isEmpty())text(g,tr("empty"),120,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }else if(page==1){
            text(g,tr(statusKey()),80,net.peercraft.client.theme.SteampunkPalette.TEXT);
            if(client.waiting())text(g,tr("remaining",Math.max(0,(client.guestExpires-System.currentTimeMillis()+999)/1000)),panelHeight()<300?116:125,net.peercraft.client.theme.SteampunkPalette.MUTED);
            text(g,tr("guest_disclosure"),panelHeight()<300?130:244,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }else{
            text(g,tr("public_rooms"),77,net.peercraft.client.theme.SteampunkPalette.ACCENT);
            text(g,tr(net.peercraft.config.PeerCraftConfig.approveStrangers()?"protected_hint":"unprotected_hint"),136,net.peercraft.client.theme.SteampunkPalette.TEXT);
            if(panelHeight()>=300)text(g,tr("quiet_hint"),195,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }
    }
    //?} else {
    /*    private void drawContent(GuiGraphicsExtractor g){
        frame(g,left(),top(),panelWidth(),panelHeight());
        text(g,tr(guestView?"waiting_title":"title"),12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
        text(g,guestView?tr("room",client.guestRoom):tr("subtitle"),28,net.peercraft.client.theme.SteampunkPalette.MUTED);
        if(page==0){
            text(g,tr("disclosure"),76,net.peercraft.client.theme.SteampunkPalette.TEXT);
            java.util.List<net.peercraft.network.admission.AdmissionClient.Entry> entries=client.entries();
            int rows=Math.max(1,Math.min(2,(panelHeight()-170)/70));
            for(int i=offset;i<Math.min(entries.size(),offset+rows);i++){
                net.peercraft.network.admission.AdmissionClient.Entry e=entries.get(i);
                text(g,tr("request",e.name,Math.max(0,(e.expires-System.currentTimeMillis()+999)/1000)),116+(i-offset)*70,net.peercraft.client.theme.SteampunkPalette.ACCENT);
            }
            if(entries.isEmpty())text(g,tr("empty"),120,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }else if(page==1){
            text(g,tr(statusKey()),80,net.peercraft.client.theme.SteampunkPalette.TEXT);
            if(client.waiting())text(g,tr("remaining",Math.max(0,(client.guestExpires-System.currentTimeMillis()+999)/1000)),panelHeight()<300?116:125,net.peercraft.client.theme.SteampunkPalette.MUTED);
            text(g,tr("guest_disclosure"),panelHeight()<300?130:244,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }else{
            text(g,tr("public_rooms"),77,net.peercraft.client.theme.SteampunkPalette.ACCENT);
            text(g,tr(net.peercraft.config.PeerCraftConfig.approveStrangers()?"protected_hint":"unprotected_hint"),136,net.peercraft.client.theme.SteampunkPalette.TEXT);
            if(panelHeight()>=300)text(g,tr("quiet_hint"),195,net.peercraft.client.theme.SteampunkPalette.MUTED);
        }
    }
*/
    //?}
    private String statusKey(){
        switch(client.guestStatus){case 1:return "pending";case 2:return "approved";case 3:return "denied";case 4:return "blocked";case 5:return "expired";case 6:return "closed";case 7:return "cancelled";case 8:return "connecting";case 9:return "rate_limit";case 10:return "unavailable";case 11:return "auth_required";default:return "no_request";}
    }
    private void update(){String value=page+":"+client.guestStatus+":"+client.entries().stream().map(e->e.id.toString()).collect(java.util.stream.Collectors.joining(","));if(!value.equals(signature)){signature=value;refresh();}}

    @Override protected void init(){controls();}
    private void refresh(){rebuildWidgets();}
    private net.minecraft.client.gui.components.Button action(int dx,int dy,int w,String title,Runnable callback,boolean primary){return addRenderableWidget(SteampunkSettingsTheme.action(left()+dx,top()+dy,w,20,net.minecraft.network.chat.Component.literal(title),b->callback.run(),primary));}
    @Override public void tick(){update();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){PeerCraftUi.setScreen(minecraft,parent!=null?parent:new net.minecraft.client.gui.screens.PauseScreen(true));}
    //? if <26.1 {
    @Override public void render(GuiGraphics g,int mx,int my,float delta){drawContent(g);super.render(g,mx,my,delta);}
    private void frame(GuiGraphics g,int x,int y,int w,int h){SteampunkSettingsTheme.frame(g,x,y,w,h,net.peercraft.client.theme.SteampunkPalette.PANEL,net.peercraft.client.theme.SteampunkPalette.BORDER);}
    private void text(GuiGraphics g,String value,int dy,int color){int line=0;for(String text:PeerCraftUi.wrap(font,value,panelWidth()-36))g.drawString(font,text,left()+18,top()+dy+12*line++,color);}
    //?} else {
    /*@Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){drawContent(g);super.extractRenderState(g,mx,my,delta);}
    private void frame(GuiGraphicsExtractor g,int x,int y,int w,int h){SteampunkSettingsTheme.frame(g,x,y,w,h,net.peercraft.client.theme.SteampunkPalette.PANEL,net.peercraft.client.theme.SteampunkPalette.BORDER);}
    private void text(GuiGraphicsExtractor g,String value,int dy,int color){int line=0;for(String text:PeerCraftUi.wrap(font,value,panelWidth()-36))g.text(font,text,left()+18,top()+dy+12*line++,color);}
    */
    //?}
}
