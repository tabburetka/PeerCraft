public class LoggerShimProbe {
 public static void main(String[] args) {
  try { net.peercraft.network.privacy.PrivateNetworkLogger.getLogger("audit");throw new AssertionError("Expected class-vs-interface failure"); }
  catch(IllegalArgumentException e){if(!e.getMessage().contains("not an interface"))throw e;System.out.println("ADDING_MARKER_ALONE_STILL_FAILS="+e.getMessage());}
 }
}
