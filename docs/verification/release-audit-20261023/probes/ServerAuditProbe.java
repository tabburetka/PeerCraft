import java.net.*;
import java.nio.file.*;
import java.util.*;
import net.peercraft.rendezvous.AccountProtocol;
import net.peercraft.rendezvous.account.AccountService;
import net.peercraft.network.account.PasswordCrypto;
public class ServerAuditProbe {
  static AccountService.AuthOkInfo ok(AccountService.Result<AccountService.AuthOkInfo> r) {
    if (r instanceof AccountService.Result.Ok<AccountService.AuthOkInfo> o) return o.value();
    throw new AssertionError(r);
  }
  public static void main(String[] args) throws Exception {
    var dir = Files.createTempDirectory("peercraft-auth-audit-");
    var service = new AccountService(dir.resolve("accounts.json"), true, System::currentTimeMillis);
    var ip = InetAddress.getLoopbackAddress();
    byte[] salt = new byte[AccountProtocol.SALT_LENGTH], hash = new byte[AccountProtocol.PASSWORD_HASH_LENGTH];
    Arrays.fill(hash, (byte) 42);
    var registered = ok(service.register("AuditPlayer", salt, hash, ip));
    var replay = ok(service.register("AuditPlayer", salt, hash, ip));
    if (registered.accountId().equals(replay.accountId())) throw new AssertionError();
    System.out.println("DUPLICATE_REGISTRATION_CREATES_DIFFERENT_UUIDS=true");
    ok(service.loginRemembered(registered.accountId(), registered.rememberToken())); // discard response
    var retry = service.loginRemembered(registered.accountId(), registered.rememberToken());
    if (!(retry instanceof AccountService.Result.Fail<?>)) throw new AssertionError();
    System.out.println("REMEMBERED_LOGIN_RETRY_AFTER_LOST_REPLY=" + ((AccountService.Result.Fail<?>)retry).reason());
    var begin = (AccountService.Result.Ok<AccountService.LoginChallengeInfo>)service.beginPasswordLogin(false, registered.accountId(), null, ip);
    byte[] proof = PasswordCrypto.hmacChallenge(hash, begin.value().challenge());
    ok(service.completePasswordLogin(begin.value().requestId(), proof)); // discard response
    var second = service.completePasswordLogin(begin.value().requestId(), proof);
    if (!(second instanceof AccountService.Result.Fail<?>)) throw new AssertionError();
    System.out.println("PASSWORD_LOGIN_RETRY_AFTER_LOST_REPLY=" + ((AccountService.Result.Fail<?>)second).reason());
    byte[] authPacket = AccountProtocol.encodeAuthOk(replay.accountId(), replay.sessionToken(), replay.rememberToken(), false, replay.friendCode(), replay.displayName());
    var decoded = AccountProtocol.decodeAuthOk(authPacket, authPacket.length);
    boolean takeover = service.loginRemembered(decoded.accountId(), decoded.rememberToken()) instanceof AccountService.Result.Ok<?>;
    if (!takeover) throw new AssertionError();
    System.out.println("CAPTURED_PLAIN_AUTH_PACKET_ALLOWS_REMEMBERED_LOGIN=true");
  }
}
