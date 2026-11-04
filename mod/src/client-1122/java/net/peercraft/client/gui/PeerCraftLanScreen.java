package net.peercraft.client.gui;
import net.minecraft.client.gui.*;
import net.minecraft.client.resources.I18n;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.input.Keyboard;
import java.util.*;
/** Keeps vanilla's game-mode, command and publish handlers while arranging all controls in one panel. */
public class PeerCraftLanScreen extends GuiShareToLan {
    private final GuiScreen parent;
    private SteampunkDialog dialog;
    private GuiButton mode, commands, start, cancel;
    private ToggleButton internet, allowUnlicensed, friends, publicRoom;
    private IdButton players;
    private SteampunkField worldName;
    private int worldY;
    public PeerCraftLanScreen(GuiScreen parent) { super(parent); this.parent=parent; }
    @Override public void initGui() {
        String previous=worldName==null?null:worldName.getText();
        super.initGui();
        for(Object item:buttonList) { GuiButton b=(GuiButton)item;
            if(b.id==104)mode=b;else if(b.id==103)commands=b;else if(b.id==101)start=b;else if(b.id==102)cancel=b;
        }
        internet=new ToggleButton(0,0,PeerCraftLang.tr("peercraft.mixin.share_to_lan.internet_play"),PeerCraftHostOptions.internetPlayRequested,value->{
            PeerCraftHostOptions.internetPlayRequested=value;relayout();
        });
        List<Integer> values=new ArrayList<>();for(int i=1;i<=8;i++)values.add(i);
        int initial=Math.max(1,Math.min(8,PeerCraftHostOptions.maxPlayers));
        players=CycleTextButton.create(0,0,150,20,values,initial,
                value->PeerCraftLang.tr("peercraft.mixin.share_to_lan.max_players")+": "+value,value->PeerCraftHostOptions.maxPlayers=value);
        allowUnlicensed=new ToggleButton(0,0,PeerCraftLang.tr("peercraft.mixin.share_to_lan.allow_unlicensed"),PeerCraftHostOptions.allowUnlicensedPlayers,
                value->PeerCraftHostOptions.allowUnlicensedPlayers=value);
        friends=new ToggleButton(0,0,PeerCraftLang.tr("peercraft.mixin.share_to_lan.friends_only"),PeerCraftHostOptions.friendsOnly,value->{
            PeerCraftHostOptions.friendsOnly=value;updateAvailability();
        });
        publicRoom=new ToggleButton(0,0,PeerCraftLang.tr("peercraft.mixin.share_to_lan.public_room"),PeerCraftHostOptions.publicRoom,value->{
            PeerCraftHostOptions.publicRoom=value;updateAvailability();
        });
        this.buttonList.add(internet);this.buttonList.add(players);this.buttonList.add(allowUnlicensed);this.buttonList.add(friends);this.buttonList.add(publicRoom);
        worldName=new SteampunkField(0, this.fontRenderer,0,0,150,20);
        worldName.setMaxStringLength(PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH);
        String seed=PeerCraftHostOptions.worldName;
        if(seed==null || seed.trim().isEmpty())seed=mc.getIntegratedServer()==null?"":mc.getIntegratedServer().getWorldName();
        worldName.setText(previous!=null?previous:seed==null?"":seed);
        Keyboard.enableRepeatEvents(true);relayout();updateAvailability();
    }
    private void updateAvailability() {
        friends.enabled=AccountClient.INSTANCE.getCurrentSession()!=null && !PeerCraftHostOptions.publicRoom;
        publicRoom.enabled=!PeerCraftHostOptions.friendsOnly;
    }
    private void place(GuiButton b,int y,int h) { b.x=dialog.contentX();b.y=y;b.width=dialog.contentWidth();b.height=h; }
    private void relayout() {
        if(internet==null || worldName==null)return;
        boolean online=PeerCraftHostOptions.internetPlayRequested;
        int rows=online?8:3;
        boolean compact=height<300;
        int preferredPitch=compact?22:28;
        int desiredHeight=(compact?36:46)+6+rows*preferredPitch+8+(compact?18:24)+(compact?22:30)+12;
        dialog=new SteampunkDialog(width,height,desiredHeight,I18n.format("lanServer.title"),360);
        int footerHeight=dialog.buttonHeight(), footerPitch=dialog.buttonPitch();
        int cancelY=dialog.top+dialog.height-12-footerHeight;
        int startY=cancelY-footerPitch;
        int pitch=Math.max(12,Math.min(28,(startY-8-dialog.contentTop())/rows));
        int h=Math.max(10,pitch-2),y=dialog.contentTop();
        place(mode,y,h);y+=pitch;place(commands,y,h);y+=pitch;place(internet,y,h);y+=pitch;
        for(GuiButton b:Arrays.asList(players,allowUnlicensed,friends,publicRoom)){b.visible=online;place(b,y,h);y+=pitch;}
        worldY=y;
        int labelWidth=Math.max(60,dialog.contentWidth()/3);
        worldName.x=dialog.contentX()+labelWidth+5;worldName.y=worldY;
        worldName.width=Math.max(1,dialog.contentWidth()-labelWidth-10);worldName.height=h;
        worldName.setVisible(online);if(!online)worldName.setFocused(false);
        place(start,startY,footerHeight);place(cancel,cancelY,footerHeight);
    }
    @Override protected void actionPerformed(GuiButton button) throws java.io.IOException {
        if(button instanceof ToggleButton){((ToggleButton)button).fire();return;}
        if(button instanceof IdButton){((IdButton)button).onPress.run();return;}
        if(button.id==101)PeerCraftHostOptions.worldName=worldName.getText().trim();
        super.actionPerformed(button);
    }
    @Override public void drawScreen(int mouseX,int mouseY,float partialTicks) {
        this.drawDefaultBackground();
        dialog.background(this.fontRenderer,width,height,0,true);
        String hovered=null;
        for(Object item:buttonList) {
            GuiButton b=(GuiButton)item;if(!b.visible)continue;
            boolean over=mouseX>=b.x && mouseX<b.x+b.width && mouseY>=b.y && mouseY<b.y+b.height;
            boolean primary=b.id==101;
            int fill=!b.enabled?SteampunkPalette.DISABLED:primary?(over?SteampunkPalette.PRIMARY_HOVER:SteampunkPalette.PRIMARY):(over?SteampunkPalette.CONTROL_HOVER:SteampunkPalette.CONTROL);
            SteampunkDialog.frame(b.x,b.y,b.width,b.height,fill,over && b.enabled?SteampunkPalette.BORDER_HOVER:SteampunkPalette.BORDER);
            String label=b.displayString;
            if(b instanceof ToggleButton)label=(((ToggleButton)b).isChecked()?"✓ ":"□ ")+label;
            String shown=this.fontRenderer.trimStringToWidth(label,Math.max(1,b.width-12));
            this.drawCenteredString(this.fontRenderer,shown,b.x+b.width/2,b.y+(b.height-8)/2,
                    !b.enabled?SteampunkPalette.MUTED:primary?SteampunkPalette.CONTROL:SteampunkPalette.TEXT);
            if(over && !shown.equals(label))hovered=label;
        }
        if(worldName.getVisible()) {
            String label=PeerCraftLang.tr("peercraft.mixin.share_to_lan.world_name");
            this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(label,Math.max(1,worldName.x-dialog.contentX()-12)),
                    dialog.contentX(),worldY+(worldName.height-8)/2,SteampunkPalette.MUTED);
            worldName.drawTextBox();
        }
        if(hovered!=null)drawHoveringText(PeerCraftUi.wrap(this.fontRenderer,hovered,dialog.contentWidth()),mouseX,mouseY);
    }
    @Override protected void keyTyped(char typedChar,int keyCode) throws java.io.IOException {
        if(keyCode==Keyboard.KEY_ESCAPE){PeerCraftUi.setScreen(mc,parent);return;}
        if(worldName.getVisible() && worldName.textboxKeyTyped(typedChar,keyCode))return;
        super.keyTyped(typedChar,keyCode);
    }
    @Override protected void mouseClicked(int x,int y,int button) throws java.io.IOException {
        super.mouseClicked(x,y,button);
        if(mc.currentScreen==this && worldName.getVisible())worldName.mouseClicked(x,y,button);
    }
    @Override public void updateScreen(){worldName.updateCursorCounter();}
    @Override public void onGuiClosed(){Keyboard.enableRepeatEvents(false);}
}
