# Hiding the friends server

The app is open source, so the address it talks to is public. These files keep the machine that holds
the database off the internet all the same: what is public is a small, replaceable proxy.

```
phone ──► renoop.duckdns.org ──► proxy VPS (nginx, TCP only)
                                      ▲
                                      │ WireGuard, dialled by the origin
                                      │
                         origin VPS: Caddy (TLS) ──► server.py on 127.0.0.1
                         nothing open to the internet but SSH
```

- **The proxy** forwards the bytes of each connection and nothing more. It holds no certificate and
  no key of the service, and cannot read a request or a signature. It is disposable.
- **The origin** terminates TLS and runs the server. It dials the proxy over WireGuard, so it opens no
  port for the service. A scan of its address finds nothing.
- **The app** needs no change when the proxy is replaced: the public name answers with a 60-second
  lifetime at DuckDNS, so a new proxy is a new address behind the same name.

What this does and does not do:

- It hides the origin's address and keeps the origin and the database up when the public address is
  attacked or blocked.
- It does not absorb a large flood. That is whatever the proxy's host filters, so pick a host whose
  tariff includes L3/L4 DDoS protection. While the proxy is flooded, Friends is unavailable; nothing is
  lost, and the proxy can be replaced in minutes.
- The proxy's host learns the origin's address (it is the other end of the tunnel). Someone who takes
  over the proxy learns it too, and can cut the service off, but cannot read or forge anything.

Nothing in this directory contains a real address or key. Each `<placeholder>` is filled in on the
machine it belongs to and never committed.

## Before you start

- A second, small VPS for the proxy (one vCPU and 512 MB is plenty), at a host with DDoS filtering.
- The origin running the friends server as `friends-server/README.md` describes (`renoop-friends.service`,
  `FRIENDS_STRAP_PEPPER` set, listening on `127.0.0.1:8787`).
- SSH to both by key. On both, in `/etc/ssh/sshd_config`: `PasswordAuthentication no`, then
  `systemctl reload ssh`. Check that it took: `sshd -T | grep -i passwordauthentication` must print
  `passwordauthentication no` (a file in `/etc/ssh/sshd_config.d/` is read first and wins, and cloud
  images often ship one), and a new session must still open by key before the old one is closed.
  Keep a second session open while changing a firewall.

## 1. Keys

On each machine:

```bash
apt install wireguard
umask 077
wg genkey | tee /etc/wireguard/private.key | wg pubkey > /etc/wireguard/public.key
```

Each machine's configuration takes its own private key and the other's public key. Private keys never
leave the machine they were made on.

## 2. The proxy

```bash
apt install nginx libnginx-mod-stream nftables
```

- `proxy-wg0.conf` to `/etc/wireguard/wg0.conf` (mode 600), with `<proxy private key>` and
  `<origin public key>` filled in. Then `systemctl enable --now wg-quick@wg0`.
- `proxy-nginx.conf` to `/etc/nginx/nginx.conf`. Check it, then load it:

  ```bash
  nginx -t && systemctl reload nginx
  ```

- `proxy-nftables.conf` to `/etc/nftables.conf`. Check it, load it with a way back, and keep it:

  ```bash
  nft -c -f /etc/nftables.conf
  (sleep 120 && nft flush ruleset) &       # if you lock yourself out, the rules clear in two minutes
  nft -f /etc/nftables.conf
  # open a NEW ssh session to confirm you can still get in, then:
  kill %1 && systemctl enable nftables
  ```

## 3. The origin

Install Caddy 2.7 or later from Caddy's own repository (the distribution's package is older and lacks
the PROXY protocol listener), then check:

```bash
caddy version
```

- `origin-wg0.conf` to `/etc/wireguard/wg0.conf` (mode 600), with `<origin private key>`,
  `<proxy public key>` and `<proxy public address>` filled in. Then
  `systemctl enable --now wg-quick@wg0`.
- Check the tunnel from the origin: `ping -c 3 10.77.0.2`. And from the proxy: `ping -c 3 10.77.0.1`.
- Keep the Caddyfile in use now: `cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-proxy`. Then
  `origin-Caddyfile` to `/etc/caddy/Caddyfile`, with the public name in place of
  `renoop.duckdns.org` if yours differs. Check it:

  ```bash
  caddy validate --config /etc/caddy/Caddyfile
  ```

  Caddy must start after the tunnel is up, because it binds the tunnel's address:

  ```bash
  mkdir -p /etc/systemd/system/caddy.service.d
  printf '[Unit]\nAfter=wg-quick@wg0.service\nRequires=wg-quick@wg0.service\n' > /etc/systemd/system/caddy.service.d/after-wireguard.conf
  systemctl daemon-reload && systemctl restart caddy
  ```

  From this restart until step 4 takes effect, the service cannot be reached under its public name:
  the name still points at this machine, and Caddy no longer listens on its public address. Do this
  step and the next in one sitting. Before moving the name, check the new path from any other
  machine, with the name resolved to the proxy by hand:

  ```bash
  curl -s --resolve renoop.duckdns.org:443:<proxy public address> https://renoop.duckdns.org/v2/info
  ```

  `<proxy public address>` is the proxy's address, as in `origin-wg0.conf`. On an origin that already
  served this name, Caddy still holds its certificate and the answer is
  `{"name":"renoop-friends","api":2,"time":…}`: the proxy, the tunnel and Caddy work, and only the
  name is left to move. On an origin that never held a certificate for the name, the request fails
  with a TLS error instead: Caddy can obtain one only once the name points at the proxy (next step),
  and it retries by itself. If the request cannot connect at all or times out, put
  `/etc/caddy/Caddyfile.before-proxy` back as `/etc/caddy/Caddyfile` and `systemctl restart caddy`
  (the service answers the old way again), then read `journalctl -u caddy -n 50` here and
  `journalctl -u nginx -n 50` on the proxy before going on.

## 4. Point the name at the proxy

In DuckDNS, set the name's address to the proxy's public address (the website, or its update call).
Then, from any other machine:

```bash
dig +short renoop.duckdns.org                      # the proxy's address
curl -s https://renoop.duckdns.org/v2/info         # {"name":"renoop-friends","api":2,"time":…}
```

The first request can take a few seconds while Caddy obtains the certificate. If it keeps failing,
`journalctl -u caddy -n 50` on the origin says why; the usual cause is the name not yet pointing at
the proxy, or the proxy's nginx not running.

A handshake that completes also proves both ends agree on the PROXY protocol: if only one side used
it, no TLS connection would open at all.

## 5. Close the origin

On the origin, `origin-nftables.conf` to `/etc/nftables.conf`, loaded the careful way:

```bash
nft -c -f /etc/nftables.conf
(sleep 120 && nft flush ruleset) &
nft -f /etc/nftables.conf
# open a NEW ssh session to confirm you can still get in, then:
kill %1 && systemctl enable nftables
```

From outside, the service still answers through the name and the origin's own address is silent:

```bash
curl -s https://renoop.duckdns.org/healthz                     # {"ok":true}
nc -vz -w 5 <origin public address> 443                        # times out or is refused
nc -vz -w 5 <origin public address> 80                         # times out or is refused
```

## 6. Give the origin a new address

The origin's present address was public for as long as the name pointed at it, and services that
record DNS history keep it. Ask the host for a new address, or rebuild the machine, **after** step 5
works. Nothing on the proxy changes: the origin dials out, so the tunnel comes back by itself.

From then on, never point a public DNS record at the origin, and do not serve anything else from it
under a name.

## Replacing the proxy

When the proxy is flooded, blocked or lost:

1. Bring up a new VPS and do section 2 on it (a new key pair, or the old proxy's key if you kept it).
2. On the origin, put the new `Endpoint` (and `PublicKey`, if it changed) in `/etc/wireguard/wg0.conf`,
   then `systemctl restart wg-quick@wg0 && systemctl restart caddy`.
3. Check the new path before moving the name, from any other machine:
   `curl -s --resolve renoop.duckdns.org:443:<new proxy address> https://renoop.duckdns.org/v2/info`
   must answer `{"name":"renoop-friends","api":2,"time":…}` (the origin already holds the
   certificate).
4. Point the name at the new proxy (section 4). Phones follow within a minute.

No app update is involved.

## Backups

`backup.py` and its timer are unchanged. Two things are not in the database and must be kept apart
from it: `FRIENDS_STRAP_PEPPER` (in `/etc/renoop-friends.env`), without which stored straps stop
matching, and the origin's WireGuard private key, without which the tunnel has to be set up again.
