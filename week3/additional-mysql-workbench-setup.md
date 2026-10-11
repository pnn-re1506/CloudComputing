# Additional Material — Connecting to Your Database with MySQL Workbench

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SSH | Secure Shell (protocol for remote terminal access) |
| GUI | Graphical User Interface (here, the MySQL Workbench desktop app) |
| TCP | Transmission Control Protocol (the connection type MySQL and SSH both use) |

**Status:** optional extension, not part of the core 3-hour session timing.
**Prerequisite:** completion of the main EC2 lab (`ec2-session-student-lab.md`) — you need a running instance with MySQL already installed (Part 7).
**Estimated time:** 20–30 minutes.

## What this adds

The core lab manages MySQL entirely through `sudo mysql` on the SSH session — a command-line-only workflow. **MySQL Workbench** is Oracle's free desktop GUI for MySQL: visual schema browsing, a query editor with autocomplete, an ER diagram designer, and data-grid editing, all running on your own laptop instead of inside the SSH session.

This material sets up:
1. A dedicated MySQL user for Workbench (not `root`, and not the passwordless `auth_socket` login the core lab uses).
2. A connection from Workbench on your laptop to MySQL on your EC2 instance — **via an SSH tunnel**, so MySQL itself never has to be exposed to the internet.
3. A quick tour of the schema you created in the core lab (`cloud26_demo`), now viewed and edited visually.

**Security note up front:** the core lab deliberately keeps MySQL listening on `localhost:3306` only — good practice, since a database with a weak password directly reachable from the internet is a classic breach vector. This material does **not** change that. Instead, it uses an SSH tunnel: your laptop connects to your instance over the same SSH port (22) already open and restricted to your IP, and the tunnel forwards MySQL traffic through that existing encrypted connection. MySQL's own network exposure never changes.

> Prefer to open MySQL directly to the internet instead (not recommended for this course, but shown for comparison)? See **Option B** at the end of this document — read the security tradeoff there before using it.

---

## Step 1 — Install MySQL Workbench on your laptop

Download from [dev.mysql.com/downloads/workbench](https://dev.mysql.com/downloads/workbench/) (free, no account required for the direct download link — skip the Oracle account prompt if it appears). Available for Windows, macOS, and Linux.

Install it like any other desktop application, then open it — you'll see a **MySQL Connections** home screen, currently empty.

---

## Step 2 — Create a dedicated MySQL user for Workbench

SSH into your instance (as in the core lab):
```bash
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
```

Log in to MySQL:
```bash
sudo mysql
```

Create a new user with a real password (never use `root` from Workbench — a dedicated, narrower-privilege user is better practice, the same principle behind this course's IAM policy work):
```sql
CREATE USER 'workbench'@'localhost' IDENTIFIED BY 'ChangeMe123!';
GRANT ALL PRIVILEGES ON cloud26_demo.* TO 'workbench'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

- Pick your own username and a real password — don't leave `ChangeMe123!` as-is.
- `'workbench'@'localhost'` — note the host is `localhost`, **not** `'%'` (any host). This is intentional: even though we're about to connect from a laptop far away, the SSH tunnel (Step 3) makes that connection *appear* to MySQL as if it's coming from `localhost` on the instance itself. We don't need — and shouldn't grant — a MySQL user that accepts connections from any host.
- `GRANT ALL PRIVILEGES ON cloud26_demo.*` scopes this user to just the one database from the core lab, not the whole server. Widen this later if you create more databases and want the same user to reach them.

---

## Step 3 — Connect Workbench through an SSH tunnel

In Workbench, click the **+** next to "MySQL Connections" to create a new connection.

Set:
- **Connection Name:** anything memorable, e.g. `cloud26-lab`
- **Connection Method:** **Standard TCP/IP over SSH**

Under the connection parameters:

| Field | Value |
|---|---|
| SSH Hostname | `<YOUR_INSTANCE_PUBLIC_IP>` (optionally `<IP>:22`) |
| SSH Username | `ubuntu` |
| SSH Key File | the path to your `cloud26-key.pem` file (e.g. `~/keys/cloud26-key.pem`) |
| MySQL Hostname | `127.0.0.1` |
| MySQL Server Port | `3306` |
| Username | `workbench` (the user from Step 2) |

Leave **SSH Password** blank — the key file handles authentication.

> **Windows/PuTTY users:** Workbench needs the key in **OpenSSH format**, the same `.pem` file you downloaded when launching the instance — **not** the `.ppk` file you may have converted it to for PuTTY. If you only have the `.ppk`, point Workbench at the original `.pem` instead.

Click **Test Connection**. You'll be prompted for the MySQL user's password (from Step 2) and, on first connection, asked to accept the SSH host's fingerprint — accept it, the same trust-on-first-use step you saw when first connecting over plain SSH.

✅ **Checkpoint:** "Test Connection" reports success. Click **OK**, then open the new connection tile.

---

## Step 4 — Explore your schema

With the connection open, Workbench shows a SQL editor and, in the left-hand **Navigator** panel, a **Schemas** tab listing databases the `workbench` user can see.

- Expand `cloud26_demo` → **Tables** to see any tables you created in the core lab (none yet, if you only ran `CREATE DATABASE` there — that's expected).
- Try creating a table visually: right-click **Tables** → **Create Table**, add a column or two, click **Apply** — Workbench generates and shows you the `CREATE TABLE` SQL before running it.
- Run a query directly: type `SELECT VERSION();` in the query editor and click the lightning-bolt **Execute** button (or `Ctrl+Enter`/`Cmd+Return`).

✅ **Checkpoint:** you can browse `cloud26_demo` and run a query, entirely through the GUI, with no SSH terminal open for the query itself (the SSH connection is still active underneath, tunneling traffic — you just don't see a terminal for it).

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Failed to connect to SSH" | Wrong SSH key path, key file permissions too open (macOS/Linux: `chmod 400` on the `.pem`, same as core lab Part 4), or the security group no longer allows your current IP on port 22 — re-check as in core lab Part 3 |
| "SSH Key File" rejects your `.pem` | You pointed it at a `.ppk` file instead — see the Windows/PuTTY note in Step 3 |
| SSH connects, but MySQL login fails | Wrong MySQL username/password from Step 2, or the user's host isn't `localhost` (re-check the `CREATE USER` statement) |
| "Test Connection" hangs indefinitely | Instance may be stopped, or your public IP changed since the security group rule was set — re-verify both |
| Schema list is empty or missing `cloud26_demo` | The `GRANT` in Step 2 didn't include that database, or `FLUSH PRIVILEGES` wasn't run — redo Step 2 |

---

## Cleanup / hardening reminder

- If you're done experimenting, consider dropping the `workbench` user or changing its password: `DROP USER 'workbench'@'localhost';` (as `sudo mysql`, same as Step 2).
- Never change `'workbench'@'localhost'` to `'workbench'@'%'` "to make it easier" — that widens the grant to accept connections from anywhere MySQL is reachable from, defeating the purpose of tunneling through SSH in the first place.
- Treat the MySQL password you set in Step 2 like any other credential — don't reuse a password you use elsewhere.

---

## Option B — Direct TCP connection (not recommended, shown for comparison)

Skip the SSH tunnel and open MySQL directly to your laptop's IP instead. **This is a real security tradeoff, not a shortcut** — walk through it once to understand what the tunnel in Step 3 was protecting you from, but default back to the tunnel for anything beyond a one-off test.

1. Edit MySQL's bind address to accept non-local connections:
```bash
sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf
```
Find the `bind-address` line (default `127.0.0.1`) and change it to `0.0.0.0`, or comment it out. Restart MySQL:
```bash
sudo systemctl restart mysql
```
2. Widen the user's host to accept remote connections:
```bash
sudo mysql
```
```sql
CREATE USER 'workbench'@'%' IDENTIFIED BY 'ChangeMe123!';
GRANT ALL PRIVILEGES ON cloud26_demo.* TO 'workbench'@'%';
FLUSH PRIVILEGES;
```
3. Open the firewall — add an inbound rule to `cloud26-sg` for **Custom TCP**, port **3306**, source **My IP** (never `0.0.0.0/0` for a database port — same discipline as every other rule in this course).
4. In Workbench, create a connection with **Connection Method: Standard (TCP/IP)** instead of the SSH variant, pointing **Hostname** directly at `<YOUR_INSTANCE_PUBLIC_IP>`, port `3306`.

**Why this is worse than Step 3's tunnel:** MySQL's own wire protocol is now doing double duty as both your application's connection and your admin GUI's connection, both reachable from the network. The SSH tunnel approach keeps MySQL's actual exposure unchanged (still `localhost`-only) and layers Workbench's traffic through a connection type (SSH) this course has already hardened and restricted by IP. If you do use Option B, **revert `bind-address` back to `127.0.0.1` and remove the port-3306 security-group rule** once you're done, to return to the core lab's private-by-default posture.

---

## What this adds on top of the core session

| | Core session | This material (SSH tunnel, recommended) | Option B (direct TCP) |
|---|---|---|---|
| MySQL client | `sudo mysql` in the SSH session | MySQL Workbench (GUI) on your laptop | MySQL Workbench (GUI) on your laptop |
| MySQL user | `root` via `auth_socket`, local only | Dedicated `workbench` user, password auth | Dedicated `workbench` user, password auth |
| MySQL's network exposure | `localhost:3306` only | Unchanged — still `localhost:3306` only | Widened to `0.0.0.0:3306`, firewalled to your IP |
| New security group rule | — | None | + port 3306 inbound |
| New attack surface | — | None (reuses existing SSH access) | MySQL's wire protocol directly reachable |
| Good teaching point | — | SSH tunneling as a general pattern for reaching private services | The real cost of "just open the port" — visible, not hidden |
