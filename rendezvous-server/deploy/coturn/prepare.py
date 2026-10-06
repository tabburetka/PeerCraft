#!/usr/bin/env python3
"""Prepare private installation files locally; never installs or starts services."""
import argparse
import ipaddress
import os
from pathlib import Path
import secrets
from urllib.parse import urlsplit


def prepare(public_ip, lan_ip, broker_url, output):
    public = ipaddress.IPv4Address(public_ip)
    local = ipaddress.IPv4Address(lan_ip)
    if not public.is_global or local.is_loopback or local.is_unspecified or local.is_multicast:
        raise ValueError("Нужны публичный IPv4 и LAN IPv4 ноутбука")
    origin = urlsplit(broker_url)
    if (origin.scheme != "https" or not origin.hostname or origin.username is not None or origin.password is not None
            or origin.query or origin.fragment or origin.path not in ("", "/")):
        raise ValueError("Нужен HTTPS-адрес broker без пути, логина и параметров")
    if any(c in broker_url for c in "\r\n\\"):
        raise ValueError("Некорректный HTTPS-адрес")
    output = Path(output)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    secret = secrets.token_hex(32)
    files = {
        "relay.env": f"PEERCRAFT_COTURN_SECRET={secret}\n",
        "relay.properties": f"""# Disabled until closed tests; copy to the EXISTING rendezvous data directory.
enabled=false
provider=coturn
advertisedUrl={broker_url.rstrip('/')}
bindHost=127.0.0.1
port=51081
tlsReverseProxy=true
coturn.publicHost={public}
coturn.port=3478
coturn.healthHost=127.0.0.1
coturn.healthPort=3478
coturn.maxConnections=2
coturn.bulkBytesPerSecond=524288
""",
        "turnserver.conf": f"""# PRIVATE: contains the same shared secret as relay.env. Never commit this file.
listening-port=3478
listening-ip=127.0.0.1
listening-ip={local}
relay-ip={local}
external-ip={public}/{local}
min-port=52000
max-port=52031
realm=peercraft
use-auth-secret
static-auth-secret={secret}
fingerprint
stale-nonce=600
max-allocate-lifetime=900
user-quota=1
total-quota=8
max-bps=1048576
bps-capacity=8388608
no-tcp
no-tls
no-dtls
no-tcp-relay
no-cli
no-multicast-peers
# All PeerCraft allocations use this server: credentials cannot relay to arbitrary Internet peers.
denied-peer-ip=0.0.0.0-255.255.255.255
allowed-peer-ip={public}
denied-peer-ip=::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
log-file=stdout
simple-log
""",
    }
    for name, content in files.items():
        descriptor = os.open(output / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(content)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-ip", required=True)
    parser.add_argument("--lan-ip", required=True)
    parser.add_argument("--broker-url", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.public_ip, args.lan_ip, args.broker_url, args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Подготовка не выполнена: {error}\n")
    print(f"Файлы подготовлены в {result}. Секреты не выводятся. Установка не выполнялась.")


if __name__ == "__main__":
    main()
