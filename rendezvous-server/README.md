# PeerCraft Rendezvous Server

A minimal standalone UDP server that lets two PeerCraft (Minecraft mod) players on
different networks find each other's public address and attempt direct UDP hole
punching. It never sees or relays actual game traffic — its only job is:

1. **Host** sends `REGISTER`; the server replies with a short room code and the host's
   own observed public `(ip, port)`.
2. **Joiner** sends `JOIN <code>`; once both sides are known, the server sends each one
   the *other's* observed address plus a shared pairing token (`PEER_FOUND`), then gets
   out of the way.
3. Both peers then punch directly at each other using that address — see the mod's own
   `README.md` ("Internet play") for the full flow and how it plugs into the rest of
   PeerCraft.

No Minecraft/Fabric dependency. The server also handles accounts, friends,
presence, public rooms, and handoff control. It does not relay game traffic.

## Build

```
./gradlew jar
```

Produces `build/libs/rendezvous-server-<version>.jar` — a single runnable jar, nothing
else to copy.

## Run

```
java -jar build/libs/rendezvous-server-1.0.0.jar [port]
```

`port` defaults to `51000` if omitted. Forward that UDP port on your router to whatever
machine you run this on (same idea as forwarding a port for a vanilla Minecraft
server, just UDP instead of TCP) — this is the *only* port-forward internet play
needs; neither player's own machine requires one.

## Local analytics dashboard

The same jar now collects aggregate statistics from requests it already receives.
No mod update or protocol change is needed, including for older clients. The dashboard
uses a separate HTTP port, `51080` by default. It binds to `127.0.0.1` by default.
For access from another device at home, bind it to the server's **LAN address**:

```
java -Dpeercraft.analytics.host=192.168.1.20 -Dpeercraft.analytics.httpPort=51080 \
  -Dpeercraft.rendezvous.dataDir=/path/to/existing/data \
  -jar rendezvous-server-1.0.0.jar 51000
```

Open `http://192.168.1.20:51080/` on a device on the same network. Replace the
example address with the server's actual LAN address. Allow TCP 51080 on the server's
local firewall if necessary; **do not forward that TCP port on the router**. Set
`-Dpeercraft.analytics.httpPort=0` to disable the dashboard. Keep the existing
`peercraft.rendezvous.dataDir` and UDP port when upgrading so account data and client
connections continue to work.

If the jar is launched by an existing service and its Java arguments are not easily
editable, create `<dataDir>/analytics.properties` instead:

```
host=192.168.1.20
httpPort=51080
```

The Java `-Dpeercraft.analytics.*` options override this file. An invalid dashboard
setting or an unavailable HTTP port is logged without stopping the UDP service.

The dashboard shows unique observed network addresses and authenticated accounts by
UTC day and calendar month, request counts by operation, successful/failed auth,
room creation and visibility, pairing attempts, friend actions, Minecraft versions
reported with new rooms, and a live room snapshot. A pairing means the rendezvous
server returned addresses; it does not prove the game connection succeeded. Repeated
UDP requests may increase event counts. Network-address uniqueness is an estimate:
players behind one router share an address, while one player may use several addresses.
Unique sets are capped at 100,000 entries per UTC day to bound memory use.
The RU/EN buttons switch the dashboard language immediately; the choice is saved in
the browser on that device.

Aggregates start when this version first runs; older history cannot be reconstructed.
Analytics are stored under `<dataDir>/analytics/` for 400 days. The store contains
HMAC hashes for unique counting, not raw IP addresses, account IDs, names, tokens,
room codes, or passwords. Keep `analytics.key` with `analytics.json` when moving the
server: replacing the key would make cross-day uniqueness inaccurate. This local HTTP
dashboard has no login, so bind it to a trusted LAN address only.

## Test

```
./gradlew test
```

Unit tests for the wire protocol and room registry, plus a live integration test that
starts a real server instance and drives it with raw UDP datagrams.

## Optional daily host reboot

For a host that should reboot every day at **05:00 Europe/Saratov**, install the
unit files in `deploy/systemd/` as root:

```
sudo install -m 644 deploy/systemd/peercraft-daily-reboot.service /etc/systemd/system/
sudo install -m 644 deploy/systemd/peercraft-daily-reboot.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now peercraft-daily-reboot.timer
systemctl list-timers peercraft-daily-reboot.timer
```

The timer is deliberately not persistent: if the host is off at 05:00, booting it
later will not trigger an immediate reboot. The machine, including active game
connections, will restart at the scheduled time. Ensure `rendezvous.service` is
enabled so it starts again after boot. Disable the schedule with
`sudo systemctl disable --now peercraft-daily-reboot.timer`.

## Optional Cloudflare TURN fallback

The UDP server also provides an optional HTTPS control service at `/v1/relay`.
Game datagrams go directly through Cloudflare TURN; this server only authorizes
matched peers, issues short credentials and exchanges relay endpoints. It is
disabled by default and needs no Cloudflare account for normal direct P2P use.

New clients advertise a stable connection attempt and their direct candidates.
Both sides receive the same authenticated attempt proof; old clients retain the
original wire format. A TURN lease requires an actual accepted REGISTER/JOIN,
valid account sessions on both sides, relay capability on both sides and host
consent. Anonymous peers still receive direct connectivity offers. Both peers
must request fallback with `directChecksFailed: true` before either receives TURN
credentials. Waiting requests do not issue provider credentials.

Existing relay leases support authenticated `/retain` and `/release` operations
with an `offerId`. A retained link can outlive the original room host for at most
40 minutes while both account sessions, relay budget and credential lifecycle
continue to be checked. The client confirms retention on a handoff worker before
sending preparation or stopping its source world. Retention does not authorize a
new match or remove budget/analytics shutdown. The final release ends the hold;
service restarts still revoke outstanding credentials before admitting new links.

### Configuration

For a home-hosted Ubuntu Server, see the [closed-test setup guide](deploy/ubuntu/README.ru.md). Its service and proxy templates are not installed automatically; preserve the existing account data directory when adapting them.

Create `data/relay.properties` only when ready to enable the service:

```properties
enabled=false
advertisedUrl=https://relay.example.com
bindHost=127.0.0.1
port=51081
# Explicitly enabled TLS reverse proxy on the same machine:
tlsReverseProxy=true
# Alternative: native HTTPS with a PKCS12 keystore; do not set tlsReverseProxy.
# keyStore=/etc/peercraft/relay.p12

# Fill these from the account's verified Realtime billing period, in UTC.
# The following placeholders are not valid values.
# billingStart=<verified ISO-8601 instant>
# billingEnd=<verified ISO-8601 instant>
billingPeriodVerified=false

# Set true only for an account whose ENTIRE Realtime usage is TURN (no SFU).
# Usage of every TURN key in that account is included in the built-in query.
exclusiveTurnAccount=false
# Shared TURN/SFU account: supply an account-wide query verified against
# Cloudflare GraphQL introspection. See the required response below.
# usageQuery=/etc/peercraft/realtime-account-usage.graphql
```

Provider secrets are environment variables on the rendezvous server only:
`CF_ACCOUNT_ID`, `CF_TURN_KEY_ID`, `CF_TURN_KEY_TOKEN`, and
`CF_ANALYTICS_TOKEN` (Account Analytics read permission). Use a dedicated TURN
key for PeerCraft. Native HTTPS additionally needs
`PEERCRAFT_RELAY_KEYSTORE_PASSWORD`. Do not put master tokens in the mod, URLs,
logs or the example properties file. Set the mod's `relay.brokerUrl` to exactly
the configured advertised HTTPS URL: the client pins it before sending its
account session token. Deployment, account creation and purchases are separate
operator actions; running the code does not perform them.

For automatic billing-period rollover, additionally set `CF_REALTIME_SUBSCRIPTION_ID`
to the verified Realtime account subscription ID and `CF_BILLING_TOKEN` to a separate
Billing Read token. The provider reads that exact subscription's current period
through Cloudflare's [Get Subscription API](https://developers.cloudflare.com/api/resources/accounts/subresources/subscriptions/methods/get_by_identifier/).
It accepts only an active monthly subscription with matching identity and valid
UTC dates. Select the actual Realtime subscription; the code does not guess it
from other subscriptions. A new period clears the persisted cutoff only after a
complete, valid account usage query succeeds for it. Failed period/usage queries
retain the cutoff and eventually disable relay through the freshness guard.
Without these optional settings, the operator must update the verified period
in `relay.properties` and restart the server each cycle. The subscription API's
availability for this account must be checked before relying on automatic rollover.

With a TLS reverse proxy, bind HTTP to loopback and have the proxy overwrite
`X-PeerCraft-Client-IP` with the actual client IP. An nginx location can use:

```nginx
location /v1/relay/ {
    client_max_body_size 8k;
    proxy_set_header X-PeerCraft-Client-IP $remote_addr;
    proxy_pass http://127.0.0.1:51081;
}
```

Serve that location from an HTTPS virtual host with a valid certificate. Never
expose port 51081 publicly in proxy mode. Responses set `Cache-Control: no-store`.
Configure proxy timeouts and request throttling to suit the deployment.

The built-in TURN-exclusive query uses Cloudflare's documented
`callsTurnUsageAdaptiveGroups` account aggregate, without key/user filters or
grouping dimensions. It includes complete UTC dates touching the verified
billing interval; boundary days can conservatively overcount. A shared-account
query must return exactly one account, with `turn` and `sfu` aliases, each either
an empty array (zero usage) or one fully aggregated `sum.egressBytes` row:

```json
{"data":{"viewer":{"accounts":[{
  "turn":[{"sum":{"egressBytes":123}}],
  "sfu":[{"sum":{"egressBytes":456}}]
}]}},"errors":null}
```

Available variables are `accountId`, `dateFrom`, `dateTo`, `datetimeStart` and
`datetimeEnd`. Verify the entire account and interval, rather than one app, key,
username or top-N list. The public SFU observability documentation currently does
not publish a corresponding account usage dataset, so the server deliberately
does not guess one. Missing SFU data, partial GraphQL errors, extra grouped rows,
unverified periods and unavailable analytics prevent credential issuance.

### Budget guard and its limits

The guard polls full account usage every 60 seconds, closes admission when the
last complete fetch is older than 120 seconds, and disables fallback at
800,000,000,000 bytes of TURN plus SFU egress. Bootstrap remains closed until
complete usage is available and outstanding restart revocations have succeeded
or expired. A budget trip is persisted and stays disabled until the operator
verifies a new billing period and restarts; it does not assume a calendar-month
reset. An analytics outage closes existing links; new matches become eligible
again after fresh complete usage below the budget and successful revocation
recovery. Expired billing boundaries also fail closed.

Cloudflare documents the first 1,000 GB per month as free across TURN and SFU,
then $0.05/GB. This guard is a conservative operational cutoff, **not a provider
spending cap or a guarantee of zero paid usage**: sampled/delayed analytics,
bursts, leaked credentials, API outages and in-flight issuance can exceed it.
Cloudflare's public documentation does not establish a hard provider-enforced
free-only cap. Keep `enabled=false` if a zero-charge guarantee is required until
that protection has been confirmed with Cloudflare.

The broker limits admission to 32 active links and new connections to
60/account/hour and 120/client-IP/hour. Renewal does not consume that allowance;
a separate credential issuance ceiling of 360/account/hour and 720/IP/hour bounds
repeated renewals from a modified client. Credentials live for
900 seconds; their usernames and quota history are atomically journaled in
`data/relay-issued.json` with restrictive file permissions. Passwords, account
session tokens and directional encryption keys are kept in memory. Closing a
link, withdrawing host consent, losing host keepalive, tripping the guard or
stopping the service schedules revocation. Failed revocations stay in the
journal and are retried; after restart they block new issuance until recovered.

### Control API and renewal

`POST /v1/relay/leases` accepts `roomCode`, decimal-string `pairToken`, UUID
`attemptId`, `role` (`host` or `joiner`), base64 `sessionToken`, and
`directChecksFailed: true`. It returns immutable lease/link/attempt identifiers,
directional AES keys, generation, `waiting`/`ready` state, optional credentials,
and the peer's published endpoint and endpoint generation. `ready` means both
peers were admitted; the clients still prove both transport directions before
sending game traffic. All later operations require the original role account's
session in `Authorization: Bearer <base64 token>`:

* `GET /v1/relay/leases/{id}` polls state and peer endpoint.
* `PUT /v1/relay/leases/{id}/endpoint` publishes `{host,port,generation,confirmed}`.
  Only public numeric IPv4 relay endpoints are accepted.
* `POST /v1/relay/leases/{id}/renew` with `{}` issues a replacement allocation's
  credentials. The same pending renewal is idempotent; the other role receives
  `409 rotation_busy` until the first commits its endpoint.
* `DELETE /v1/relay/leases/{id}` closes both sides and revokes their credentials.

Publish a candidate with `confirmed:false`, perform authenticated probes, then
commit the same endpoint with `confirmed:true`. Renewal preserves the old
published endpoint generation until the replacement is published and retains
the previous credentials through commit plus a 30-second overlap. Clients must
replace the TURN allocation when the provider changes the username. Errors use
`{"error":"code"}`; budget and analytics failures use
`budget_exhausted` and `analytics_unavailable`. HTTP polling must continue during
an active lease; a link without control activity for 120 seconds expires.

Reference documentation: [TURN credentials](https://developers.cloudflare.com/realtime/turn/generate-credentials/),
[TURN analytics](https://developers.cloudflare.com/realtime/turn/analytics/),
[Realtime pricing](https://developers.cloudflare.com/realtime/sfu/platform/pricing/),
[billing usage periods](https://developers.cloudflare.com/billing/manage/billable-usage/).
