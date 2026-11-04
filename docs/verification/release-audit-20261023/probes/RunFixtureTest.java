import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;
public class RunFixtureTest {
 public static void main(String[] args) {
  var request=LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectMethod("net.peercraft.network.modsync.ModSyncLoopbackIntegrationTest#transfersRealAppleSkinJarWhenAvailable(java.nio.file.Path)")).build();
  var summary=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(summary);launcher.execute(request);
  summary.getSummary().printTo(new PrintWriter(System.out));summary.getSummary().printFailuresTo(new PrintWriter(System.out));
  if(summary.getSummary().getTestsSucceededCount()!=1)throw new AssertionError("Fixture did not pass");
 }
}
