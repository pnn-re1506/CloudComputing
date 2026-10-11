# Additional Material — Tomcat Web Admin (Manager App): Deploying Web Apps Through the Browser

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| SSH | Secure Shell (protocol for remote terminal access) |
| XML | Extensible Markup Language (the format of Tomcat's config files) |
| GUI | Graphical User Interface (here, the browser-based admin page) |
| WAR | Web Application Archive (the packaged `.war` file format Tomcat deploys) |
| CIDR | Classless Inter-Domain Routing (the notation used to describe an IP address range, e.g. `10.0.0.0/24`) |
| TLS / SSL | Transport Layer Security / Secure Sockets Layer (the encryption behind HTTPS; SSL is the older name for the same idea) |
| VPN | Virtual Private Network |

**Status:** optional extension, not part of the core 3-hour session timing.
**Prerequisite:** completion of the main EC2 lab (`ec2-session-student-lab.md`) — you need a running instance with Tomcat 10 already installed and serving on port 8080.
**Estimated time:** 20–30 minutes.

## What this adds

Tomcat ships with a built-in web admin page called the **Manager app** — a browser-based dashboard for deploying, starting, stopping, and undeploying web applications (`.war` files) without touching the command line. By default it's installed but **locked down**: no user can log in, and even with a valid login, the app only accepts connections from `localhost` (the server itself), not from your laptop's browser.

This material sets up:
1. A Tomcat user account with permission to use the Manager app.
2. Access to the Manager app from your own laptop's browser (not just from inside the instance).
3. A live demo of uploading a `.war` file through the browser instead of the command line.

**Security note up front:** the Manager app is a well-known target — if left open to the internet with a weak or default password, it's a direct path to full control of the server (an attacker who logs in can deploy a malicious `.war` and get code execution). This material follows the same pattern already used for SSH in the core lab: **restrict access to your own IP address**, never leave it open to `0.0.0.0/0`.

---

## Step 1 — Create a Manager app user

SSH into your instance (as in the core lab):
```bash
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
```

Edit Tomcat's user database:
```bash
sudo nano /opt/tomcat/conf/tomcat-users.xml
```

Find the closing `</tomcat-users>` tag near the end of the file, and add these two lines **just before it**:
```xml
<role rolename="manager-gui"/>
<user username="admin" password="ChangeMe123!" roles="manager-gui"/>
```

- `manager-gui` is the role that grants access to the Manager app's HTML pages (as opposed to `manager-script`, `manager-jmx`, or `manager-status`, which are for scripted/API access — not needed here).
- Pick your own username and a real password — don't leave `ChangeMe123!` as-is. Avoid reusing your AWS account password.
- **Never grant `manager-script` or `manager-jmx` to this same user** alongside `manager-gui` — combining them is a well-documented Tomcat hardening anti-pattern (a compromised GUI session would then also carry scripting/JMX-level control).

Save and exit (`Ctrl+O`, `Enter`, `Ctrl+X` in nano).

---

## Step 2 — Allow your laptop's IP to reach the Manager app

By default, Tomcat's Manager app only accepts connections from the loopback address (`127.0.0.1` / `::1`) — i.e., only from a browser running *on the server itself*, which your laptop is not. This is a safety default, and loosening it is exactly the step that needs care.

Create a context override for the Manager app (this approach — a `conf/Catalina/localhost/manager.xml` file — survives Tomcat upgrades, unlike editing the file bundled inside the Manager `.war` itself):

```bash
sudo nano /opt/tomcat/conf/Catalina/localhost/manager.xml
```

Paste the block below, then replace `YOUR_LAPTOP_PUBLIC_IP` (no angle brackets — this goes inside an XML attribute, and literal `<`/`>` characters would break the file) with your own current public IP. Find it by visiting `https://checkip.amazonaws.com` from your laptop's browser:

```xml
<Context privileged="true" antiResourceLocking="false"
         docBase="${catalina.home}/webapps/manager">
  <CookieProcessor className="org.apache.tomcat.util.http.Rfc6265CookieProcessor"
                    sameSiteCookies="strict" />
  <Valve className="org.apache.catalina.valves.RemoteCIDRValve"
         allow="127.0.0.0/8,::1/128,YOUR_LAPTOP_PUBLIC_IP/32" />
</Context>
```

- The `RemoteCIDRValve`'s `allow` list is a comma-separated set of CIDR ranges. `127.0.0.0/8` and `::1/128` are the original loopback defaults — **keep both**, then add your own IP as a `/32` (a CIDR block containing exactly one address).
- If your IP changes later (different Wi-Fi, VPN, mobile hotspot), the Manager app will stop responding until you edit this file again and restart Tomcat — the same gotcha as the SSH security-group rule from the core lab.
- **Do not** replace the `allow` list with `0.0.0.0/0` or remove the `Valve` element entirely — either would open the Manager app to the whole internet.

Save and exit, then restart Tomcat to pick up both changes:
```bash
sudo systemctl restart tomcat
sudo systemctl status tomcat
```

---

## Step 3 — Open the firewall port (if not already open)

The Manager app is served over the same port Tomcat already uses (8080), which the core lab's security group (`cloud26-sg`) already opens to `0.0.0.0/0` for the main application. No new security-group rule is needed here — the `RemoteCIDRValve` from Step 2 is what actually restricts who can reach the Manager app specifically, even though port 8080 itself stays open to everyone for the rest of the site.

> If you completed the HTTPS additional material and are running Tomcat's native SSL connector (Path B) on port 8443 instead, the Manager app is reachable there too, over HTTPS, at the same `/manager/html` path.

---

## Step 4 — Log in and deploy a `.war` file

From your laptop's browser, visit:
```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/manager/html
```

Enter the username/password you set in Step 1. You should see the **Tomcat Web Application Manager** page, listing currently deployed apps (at minimum, the ROOT app showing the default splash page).

**To deploy a `.war` file:**
1. Scroll to the **Deploy** section → **WAR file to deploy**.
2. Click **Choose File**, select a `.war` file from your laptop.
3. Click **Deploy**.
4. The app appears in the applications table above, with a **Context Path** matching the `.war` filename (e.g. `myapp.war` deploys to `/myapp`).
5. Visit `http://<YOUR_INSTANCE_PUBLIC_IP>:8080/myapp` to see it running.

**To undeploy or stop an app:** use the **Stop**, **Start**, **Reload**, or **Undeploy** links next to it in the applications table.

Don't have a `.war` file handy? Any simple servlet or JSP project packaged with `jar cvf myapp.war -C build/ .` (or your build tool's WAR-packaging task, e.g. Maven's `mvn package` with `packaging: war`) will do for the demo.

✅ **Checkpoint:** you can deploy and see a `.war` file running, entirely through the browser, no SSH session needed for the deploy step itself.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `403` / blank page at `/manager/html` | `RemoteCIDRValve`'s `allow` list doesn't include your current IP — re-check Step 2, and re-verify your IP hasn't changed |
| Login prompt rejects your credentials | Typo in `tomcat-users.xml`, or Tomcat wasn't restarted after the edit — re-check Step 1 and restart |
| `manager.xml` edit seems to have no effect | Config wasn't reloaded — a restart (`systemctl restart tomcat`), not just a Manager-app **Reload**, is required for `tomcat-users.xml` and `manager.xml` changes |
| Deploy succeeds but the app doesn't run correctly | Not a Manager-app issue — normal Java web app debugging: check `sudo tail -f /opt/tomcat/logs/catalina.out` |
| `404` at the app's context path after deploy | Confirm the exact context path shown in the Manager app's table — it's derived from the `.war` filename, not assumed |
| Browser shows a security warning even though the URL is `http://` | Not a Manager-app or Tomcat problem — your browser is likely auto-upgrading the request to HTTPS on its own and then failing, since nothing here serves TLS. Type the `http://` URL out fully rather than clicking an autocomplete suggestion, or disable "Always use secure connections" in your browser's security settings (Chrome/Edge: `chrome://settings/security`) |

---

## Cleanup / hardening reminder

- If you're done experimenting, consider removing or commenting out the `<user>` line in `tomcat-users.xml` (or at minimum changing the password) so the Manager app isn't left reachable with a known credential.
- Never leave the `RemoteCIDRValve`'s `allow` list open to `0.0.0.0/0` — it should always be scoped to specific IPs, the same discipline as the SSH security-group rule.
- If continuing to use this instance in later sessions, treat `tomcat-users.xml` like any other credentials file: it's plaintext, so don't reuse a password you use elsewhere.

---

## What this adds on top of the core session

| | Core session | This material |
|---|---|---|
| Deploying a web app | Not covered — Tomcat serves only its own default splash page | A `.war` file deployed via browser upload |
| New Tomcat file touched | — | `conf/tomcat-users.xml`, `conf/Catalina/localhost/manager.xml` |
| New access-control concept | Security group (network-level, AWS) | `RemoteCIDRValve` (application-level, inside Tomcat) — same idea, different layer |
| Good teaching point | — | Defense-in-depth: two independent IP allowlists (security group + Tomcat valve) guard the same admin surface |
