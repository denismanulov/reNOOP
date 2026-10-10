# Friends Server Deployment Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put in the repository the files and the runbook that hide the friends server's machine: a small TCP-only proxy in front, a WireGuard link between the two, and an origin that accepts nothing from the internet.

**Architecture:** The public name (`renoop.duckdns.org`) points at a disposable proxy VPS. nginx there forwards TCP 443 unchanged over WireGuard to the origin, with a PROXY protocol header so the origin still sees each caller's address. Caddy on the origin listens only on the WireGuard address, terminates TLS, and hands requests to `server.py` on loopback. The origin dials the proxy, not the other way round, so the origin opens no port at all.

**Tech Stack:** nginx (`stream` module), WireGuard, Caddy 2.7 or later, nftables, systemd.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-10-friends-keys-and-straps-design.md`, section 8.
- **This plan writes files. It does not log in to any machine, change any DNS record, or run any of the runbook's commands.** Deployment is the operator's, by the runbook.
- No real address, key, token or secret appears in any file. Placeholders are written `<like this>` and each is explained where it is used.
- The proxy holds no certificate and no private key of the service, and sees only ciphertext.
- One deviation from the spec's section 8, on purpose: the spec has the origin accept WireGuard from the proxy; here the origin dials out to the proxy instead. The origin then has no inbound port for the service at all, and moving the origin to a new address needs no change on the proxy. The runbook says so.
- `nginx`, `caddy`, `nft` and `wg` are not installed on the development Mac, so these files cannot be validated here. Each has a validation command in the runbook that the operator runs on the target machine before applying it. Say so in the report; do not claim the configurations were tested.
- Addresses used throughout: WireGuard network `10.77.0.0/24`, origin `10.77.0.1`, proxy `10.77.0.2`, WireGuard port `51820/udp`.
- Commit subjects `feature:` / `fix:` / `docs:`; no `Co-Authored-By`; stage named files only.

## File Structure

| File | What it is |
|---|---|
| `friends-server/deploy/README.md` | The runbook: order of steps, checks, moving the origin, replacing the proxy. |
| `friends-server/deploy/proxy-nginx.conf` | The proxy's whole nginx configuration. |
| `friends-server/deploy/proxy-wg0.conf` | WireGuard on the proxy. |
| `friends-server/deploy/proxy-nftables.conf` | The proxy's firewall. |
| `friends-server/deploy/origin-wg0.conf` | WireGuard on the origin. |
| `friends-server/deploy/origin-Caddyfile` | Caddy on the origin. Replaces `friends-server/Caddyfile.example`. |
| `friends-server/deploy/origin-nftables.conf` | The origin's firewall. |
| `friends-server/README.md` | One paragraph pointing at `deploy/`. |

---

### Task 1: The configuration files

**Files:**
- Create: `friends-server/deploy/proxy-nginx.conf`, `proxy-wg0.conf`, `proxy-nftables.conf`, `origin-wg0.conf`, `origin-nftables.conf`
- Move and rewrite: `friends-server/Caddyfile.example` to `friends-server/deploy/origin-Caddyfile`

**Interfaces:**
- Produces: the six files the runbook of Task 2 installs, with the placeholders `<proxy public address>`, `<proxy private key>`, `<proxy public key>`, `<origin private key>`, `<origin public key>`.

- [ ] **Step 1: The proxy's nginx**

Create `friends-server/deploy/proxy-nginx.conf`:

```nginx
# /etc/nginx/nginx.conf on the proxy.
#
# TCP only. This machine holds no certificate and no key of the friends service: it forwards the bytes
# of every connection to the origin over WireGuard and never sees inside them. The PROXY protocol
# header tells the origin which address each connection came from, so the origin's own limits still
# count real callers.
#
# Debian/Ubuntu: apt install nginx libnginx-mod-stream

user www-data;
worker_processes auto;
pid /run/nginx.pid;
include /etc/nginx/modules-enabled/*.conf;

events {
    worker_connections 4096;
}

stream {
    # Many connections from one address is the cheap attack. Hold it here, before the tunnel.
    limit_conn_zone $binary_remote_addr zone=per_address:10m;

    upstream origin {
        server 10.77.0.1:443;
    }

    server {
        listen 443;
        listen [::]:443;

        limit_conn per_address 20;
        proxy_connect_timeout 5s;
        proxy_timeout 60s;
        proxy_protocol on;
        proxy_pass origin;
    }
}
```

- [ ] **Step 2: WireGuard on the proxy**

Create `friends-server/deploy/proxy-wg0.conf`:

```ini
# /etc/wireguard/wg0.conf on the proxy. chmod 600.
#
# The proxy listens; the origin dials in and keeps the link up. So the origin needs no open port, and
# nothing here names the origin's address.

[Interface]
Address = 10.77.0.2/24
ListenPort = 51820
PrivateKey = <proxy private key>

[Peer]
# The origin.
PublicKey = <origin public key>
AllowedIPs = 10.77.0.1/32
```

- [ ] **Step 3: The proxy's firewall**

Create `friends-server/deploy/proxy-nftables.conf`:

```
#!/usr/sbin/nft -f
# /etc/nftables.conf on the proxy.
#
# This file REPLACES the whole ruleset. On a machine that runs anything else that manages nftables
# (Docker, a hosting panel), merge these rules instead of loading the file.

flush ruleset

table inet filter {
	chain input {
		type filter hook input priority 0; policy drop;

		ct state established,related accept
		iif "lo" accept
		ip protocol icmp accept
		ip6 nexthdr icmpv6 accept

		# SSH, by key only (the runbook turns passwords off). Narrow it to your own address if that is fixed.
		tcp dport 22 accept

		# The friends service, forwarded as it arrives.
		tcp dport 443 accept

		# WireGuard, for the origin to dial in.
		udp dport 51820 accept
	}

	chain forward {
		type filter hook forward priority 0; policy drop;
	}

	chain output {
		type filter hook output priority 0; policy accept;
	}
}
```

- [ ] **Step 4: WireGuard on the origin**

Create `friends-server/deploy/origin-wg0.conf`:

```ini
# /etc/wireguard/wg0.conf on the origin. chmod 600.
#
# The origin dials the proxy and keeps the link up from its side, so it accepts nothing from outside.
# Replacing the proxy means changing the peer below, and nothing else.

[Interface]
Address = 10.77.0.1/24
PrivateKey = <origin private key>

[Peer]
# The proxy.
PublicKey = <proxy public key>
Endpoint = <proxy public address>:51820
AllowedIPs = 10.77.0.2/32
PersistentKeepalive = 25
```

- [ ] **Step 5: Caddy on the origin**

```bash
git mv friends-server/Caddyfile.example friends-server/deploy/origin-Caddyfile
```

Then replace the file's content with:

```
# /etc/caddy/Caddyfile on the origin. Needs Caddy 2.7 or later (the PROXY protocol listener is built in
# from that version; Debian's and Ubuntu's own packages are older, so install from Caddy's repository).
#
# Caddy listens only on the WireGuard address, so nothing on the internet can reach it. It terminates
# TLS here, on the machine that holds the data: the proxy in front forwards bytes and cannot read them.
# The certificate is obtained by the TLS-ALPN challenge, which arrives on port 443 through the proxy
# like any other connection.
#
# Replace the name below with the one that points at the proxy.

{
	# No listener on port 80: nothing forwards it here.
	auto_https disable_redirects

	servers {
		listener_wrappers {
			# The proxy says which address each connection came from. Only the proxy is believed.
			proxy_protocol {
				timeout 5s
				allow 10.77.0.2/32
			}
			tls
		}
	}
}

renoop.duckdns.org {
	bind 10.77.0.1

	tls {
		issuer acme {
			disable_http_challenge
		}
	}

	encode gzip
	request_body {
		max_size 256KB
	}

	# Caddy passes the caller's address on as X-Forwarded-For; the server reads the last hop of it
	# (FRIENDS_TRUST_PROXY=1, the default).
	reverse_proxy 127.0.0.1:8787
}
```

- [ ] **Step 6: The origin's firewall**

Create `friends-server/deploy/origin-nftables.conf`:

```
#!/usr/sbin/nft -f
# /etc/nftables.conf on the origin.
#
# The origin accepts SSH and nothing else from the internet. The friends service is reached only from
# the proxy, over WireGuard, which the origin itself dials: no port is open for it.
#
# This file REPLACES the whole ruleset. On a machine that runs anything else that manages nftables
# (Docker, a hosting panel), merge these rules instead of loading the file.

flush ruleset

table inet filter {
	chain input {
		type filter hook input priority 0; policy drop;

		ct state established,related accept
		iif "lo" accept
		ip protocol icmp accept
		ip6 nexthdr icmpv6 accept

		# SSH, by key only (the runbook turns passwords off). Narrow it to your own address if that is fixed.
		tcp dport 22 accept

		# The friends service: from the proxy, through the tunnel, and from nowhere else.
		iifname "wg0" ip saddr 10.77.0.2 tcp dport 443 accept
	}

	chain forward {
		type filter hook forward priority 0; policy drop;
	}

	chain output {
		type filter hook output priority 0; policy accept;
	}
}
```

- [ ] **Step 7: Check that nothing real slipped in**

```bash
grep -rn -E '([0-9]{1,3}\.){3}[0-9]{1,3}' friends-server/deploy | grep -v -E '10\.77\.0\.|127\.0\.0\.1'
grep -rn -i -E 'PrivateKey|PublicKey|token' friends-server/deploy | grep -v -E '<[a-z ]+>'
```

Expected: both print nothing. Every address is the tunnel's or loopback, and every key is a placeholder.

- [ ] **Step 8: Commit**

```bash
git add friends-server/deploy/proxy-nginx.conf friends-server/deploy/proxy-wg0.conf friends-server/deploy/proxy-nftables.conf friends-server/deploy/origin-wg0.conf friends-server/deploy/origin-Caddyfile friends-server/deploy/origin-nftables.conf
git commit -m "feature: deployment files that keep the friends server's machine off the internet"
```

`git mv` already staged the removal of `Caddyfile.example`; `git status --short friends-server` should show it as a rename (`R`), and nothing else of this task unstaged.

---

### Task 2: The runbook

**Files:**
- Create: `friends-server/deploy/README.md`
- Modify: `friends-server/README.md` (one paragraph in "Running it")

**Interfaces:**
- Consumes: the six files of Task 1.

- [ ] **Step 1: Write the runbook**

Create `friends-server/deploy/README.md`:

````markdown
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
  `systemctl reload ssh`. Keep a second session open while changing a firewall.

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
- `origin-Caddyfile` to `/etc/caddy/Caddyfile`, with the public name in place of
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

  It cannot get its certificate until the public name points at the proxy (next step). That is
  expected; it retries by itself.

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
3. Point the name at the new proxy (section 4). Phones follow within a minute.

No app update is involved.

## Backups

`backup.py` and its timer are unchanged. Two things are not in the database and must be kept apart
from it: `FRIENDS_STRAP_PEPPER` (in `/etc/renoop-friends.env`), without which stored straps stop
matching, and the origin's WireGuard private key, without which the tunnel has to be set up again.
````

- [ ] **Step 2: Point at it from the server's README**

In `friends-server/README.md`, in "Running it", replace the sentence

```
`renoop-friends.service` runs the server as a throwaway user with the database in
`/var/lib/renoop-friends` and reads the pepper from `/etc/renoop-friends.env`.
```

with

```
`renoop-friends.service` runs the server as a throwaway user with the database in
`/var/lib/renoop-friends` and reads the pepper from `/etc/renoop-friends.env`.

The server never faces the internet itself. [`deploy/`](deploy/README.md) has the arrangement in
front of it, and why: a small TCP-only proxy under the public name, a WireGuard link, and Caddy on
this machine terminating TLS on the tunnel's address only. The machine that holds the database opens
no port for the service and its address is not published.
```

- [ ] **Step 3: Check the README's promises against the files**

```bash
for f in proxy-nginx.conf proxy-wg0.conf proxy-nftables.conf origin-wg0.conf origin-Caddyfile origin-nftables.conf; do
  grep -q "$f" friends-server/deploy/README.md && test -f "friends-server/deploy/$f" && echo "ok $f" || echo "MISSING $f"
done
grep -c 'Caddyfile.example' friends-server/README.md friends-server/deploy/README.md
```

Expected: six `ok` lines, and `0` for both files in the last command (the old example file is gone and nothing names it).

- [ ] **Step 4: Commit**

```bash
git add friends-server/deploy/README.md friends-server/README.md
git commit -m "docs: a runbook for putting the friends server behind a proxy"
```

- [ ] **Step 5: Report what was not validated**

Say plainly in the report: the six configuration files were written but not run through `nginx -t`, `caddy validate`, `nft -c` or `wg-quick`, because none of those tools exists on the development machine. The runbook has the operator run each check on the target before applying the file. Nothing was deployed.

---

## Done when

- `friends-server/deploy/` holds the runbook and six files, with no real address, key or token in any.
- `friends-server/Caddyfile.example` is gone (moved), and the server's README points at `deploy/`.
- The report says the configurations are unvalidated and undeployed.
