# Lab: Deploying a Web/DB Server on Amazon EC2

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| AMI | Amazon Machine Image |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| CLI | Command Line Interface |
| SSH | Secure Shell (protocol for remote terminal access) |
| VPC | Virtual Private Cloud |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| TLS / SSL | Transport Layer Security / Secure Sockets Layer (the encryption behind HTTPS) |
| RSA | Rivest–Shamir–Adleman (a public-key cryptography algorithm, used here for SSH key pairs) |
| LTS | Long Term Support (a software release with extended maintenance) |
| VPN | Virtual Private Network |
| GiB | Gibibyte (a unit of data storage) |
| GUI | Graphical User Interface (a browser-based admin page, or a desktop app like MySQL Workbench) |
| NAT | Network Address Translation (rewrites the source IP of outbound packets, e.g. so many devices can share one public IP) |

**Duration:** 3 hours
**You will need:** your own AWS account, your IAM user `CTO` credentials, a laptop with an SSH client and the AWS CLI installed.
**Region:** use `ap-southeast-1` (Asia Pacific — Singapore) throughout — for launching your instance in the Console and as the default region for your CLI profile.

By the end of this lab you will have launched an Ubuntu 26.04 EC2 instance, connected to it over SSH, controlled it with the AWS CLI as user `CTO`, and installed both a MySQL database and a Tomcat 10 web server on it.

**Next session:** `labs/s3-session-student-lab.md` — S3 and the AWS SDK for Java, a separate 3-hour session that continues from where this one leaves off.

---

## Part 1 — Introduction to EC2

EC2 (Elastic Compute Cloud) provides resizable virtual servers ("instances") in the cloud. Key terms you'll use today:

| Term | Meaning |
|---|---|
| **AMI** | Amazon Machine Image — the OS template an instance boots from |
| **Instance type** | The hardware profile (vCPU/RAM) — we'll use a free-tier-eligible type |
| **Key pair** | SSH key used to log in, instead of a password |
| **Security group** | A virtual firewall attached to your instance |
| **Public IP** | The address you'll SSH/browse to — changes each time you stop/start the instance |

**Free tier reminder:** your account gets 750 hours/month of a `t2.micro` or `t3.micro` instance free for 12 months. Always **stop** (don't leave running) your instance when you're done for the day.

---

## Part 2 — Launch your EC2 instance

1. Sign in to the [AWS Management Console](https://console.aws.amazon.com/) and go to **EC2**.
2. Switch the region selector (top-right of the Console) to **Asia Pacific (Singapore) `ap-southeast-1`** — do this before launching, so your instance and your later CLI commands are in the same region.
3. Click **Launch instance**.
4. **Name:** `cloud26-lab`
5. **Application and OS Images (AMI):** search for **Ubuntu**, choose **Ubuntu Server 26.04 LTS**, 64-bit (x86).
6. **Instance type:** choose the one labeled **"Free tier eligible"** (typically `t2.micro` or `t3.micro`).
7. **Key pair (login):**
   - Click **Create new key pair**.
   - Name: `cloud26-key`
   - Type: RSA — Format: `.pem` (Mac/Linux) or `.ppk` (Windows + PuTTY)
   - Click **Create key pair** — it downloads automatically. **Save it somewhere you'll remember** (e.g. `~/keys/`). You cannot re-download it later.
8. **Network settings:**
   - Keep the default VPC/subnet.
   - Auto-assign public IP: **Enable**
   - Firewall (security group): **Create security group**, name it `cloud26-sg`.
   - Check **"Allow SSH traffic from"** → select **My IP**.
9. **Configure storage:** leave the default (8 GiB) — within free tier.
10. Click **Launch instance**.
11. Go to **Instances**, and wait until **Instance state = Running** and **Status check = 2/2 checks passed** (about 1–2 minutes).

✅ **Checkpoint:** you have a running Ubuntu 26.04 instance.

---

## Part 3 — Check the firewall (Security Group) for SSH

1. Select your instance → **Security** tab → click the security group name (`cloud26-sg`).
2. Open **Inbound rules** and confirm there's a rule for **SSH (port 22)** with your IP as the source.
3. ⚠️ If you switch networks later (different Wi-Fi, VPN) and SSH stops connecting, come back here, **Edit inbound rules**, and update the source to **My IP** again.

**If you're on public/shared Wi-Fi (coffee shop, campus, coworking space) and this still doesn't work:** some such networks share one public IP across many users through carrier-grade NAT — a shared address translator operated by the network, not something you control — so the IP AWS's "My IP" button detects for one request can already be stale by the time your actual SSH connection goes out. Symptom: SSH just hangs and times out, rather than failing immediately. If re-checking "My IP" doesn't fix it after a try or two, temporarily set the source to **Anywhere** (`0.0.0.0/0`) to confirm SSH itself works, then switch back once you're off that network — this is fine for a disposable course instance, but never do this on infrastructure that matters.

---

## Part 4 — Connect via SSH

1. Select your instance → click **Connect** → **SSH client** tab. Note the example command and username (`ubuntu`).
2. Open a terminal on your laptop.

**macOS/Linux:**
```bash
chmod 400 ~/keys/cloud26-key.pem
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
```

**Windows (PowerShell, built-in OpenSSH):**
```powershell
ssh -i cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
```
If you get a permissions error on the key file, run:
```powershell
icacls.exe "cloud26-key.pem" /reset
icacls.exe "cloud26-key.pem" /grant:r "$($env:username):(R)"
icacls.exe "cloud26-key.pem" /inheritance:r
```

**Windows (PuTTY):** convert `.pem` to `.ppk` with PuTTYgen first, then connect with host `ubuntu@<public-ip>` and browse to the `.ppk` under Connection > SSH > Auth > Credentials.

3. Type `yes` if prompted to accept the host key fingerprint.
4. Once connected, verify:
```bash
lsb_release -a
```

✅ **Checkpoint:** you're logged into your instance as `ubuntu` and see Ubuntu 26.04 in the output.

**Common issues:**
- *Connection timed out* → your IP isn't allowed in the security group (see Part 3), or the instance isn't running.
- *Permission denied (publickey)* → check you're using username `ubuntu` and the correct key file.

---

## — Break (10 min) —

---

## Part 5 — AWS CLI setup as IAM user CTO

1. Confirm the CLI is installed:
```bash
aws --version
```
2. Get access keys for your `CTO` IAM user: Console → **IAM** → **Users** → `CTO` → **Security credentials** tab → **Create access key** → use case **Command Line Interface (CLI)** → Create. Copy the **Access Key ID** and **Secret Access Key** (shown once).
3. Configure a named CLI profile:
```bash
aws configure --profile CTO
```
Enter the Access Key ID, Secret Access Key, region `ap-southeast-1`, and output format `json`.

4. Test it:
```bash
aws sts get-caller-identity --profile CTO
```
You should see the ARN for `CTO`.

---

## Part 6 — Stop/Start your instance via CLI

Find your instance ID (Console → Instances, or):
```bash
aws ec2 describe-instances --profile CTO \
  --query "Reservations[].Instances[].[InstanceId,State.Name]" --output table
```

Try stopping it:
```bash
aws ec2 stop-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO
```

If `CTO` doesn't yet have permission, you'll see an `UnauthorizedOperation` error — that's expected. Your instructor will walk through attaching an IAM policy to `CTO` that allows only `StartInstances`/`StopInstances`/`DescribeInstances` on **your specific instance**.

Once the policy is attached, retry:
```bash
aws ec2 stop-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO

# wait ~20-30 seconds, then check status
aws ec2 describe-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO \
  --query "Reservations[].Instances[].State.Name"

aws ec2 start-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO
```

⚠️ **Important:** your instance's public IP **changes** every time you stop/start it (unless you use an Elastic IP, which we aren't in this core lab). Get the new IP:
```bash
aws ec2 describe-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO \
  --query "Reservations[].Instances[].PublicIpAddress" --output text
```

SSH back in with the new IP to confirm it's reachable (repeat the command from Part 4, updating the IP — and re-check Part 3 if your own laptop's IP has also changed).

✅ **Checkpoint:** you can stop/start your instance using the AWS CLI as `CTO`, and reconnect afterward.

---

## Part 7 — Install MySQL

On your SSH session:
```bash
sudo apt update
sudo apt install -y mysql-server mysql-client
mysql --version
sudo systemctl status mysql
```

> This installs the current MySQL build from Ubuntu's own repository automatically — no version number to type. On Ubuntu 26.04, `mysql --version` should report something like `8.4.x`.

Secure the installation (answer the prompts):
```bash
sudo mysql_secure_installation
```

Log in and run a quick test:
```bash
sudo mysql
```
```sql
SELECT VERSION();
CREATE DATABASE cloud26_demo;
SHOW DATABASES;
EXIT;
```

> **Note:** by default, `root` on Ubuntu logs in via `sudo mysql` (no password needed locally). If you want a password-based login instead, ask your instructor to show the `ALTER USER ... IDENTIFIED WITH caching_sha2_password` step.

MySQL listens only on `localhost:3306` — we are **not** exposing the database to the internet today; that's intentional and good practice.

✅ **Checkpoint:** `SHOW DATABASES;` lists `cloud26_demo`.

> Prefer a visual database tool over `sudo mysql`? See **Additional Material: `additional-mysql-workbench-setup.md`** — connects MySQL Workbench (a free desktop GUI) to your database through an SSH tunnel, without changing MySQL's `localhost`-only setup above.

---

## Part 8 — Install Tomcat 10 (port 8080)

```bash
sudo apt update
sudo apt install -y openjdk-25-jre-headless
java -version
```

Create a dedicated user and download Tomcat 10 (your instructor will confirm the current version number/URL):
```bash
sudo useradd -m -U -d /opt/tomcat -s /bin/false tomcat

cd /tmp
curl -O https://dlcdn.apache.org/tomcat/tomcat-10/v10.1.60/bin/apache-tomcat-10.1.60.tar.gz

sudo mkdir -p /opt/tomcat
sudo tar xzvf apache-tomcat-10.1.60.tar.gz -C /opt/tomcat --strip-components=1
sudo chown -R tomcat: /opt/tomcat
sudo chmod -R u+x /opt/tomcat/bin
```

Find your Java path (you'll need it in the next step):
```bash
readlink -f $(which java)
```

Create the systemd service file:
```bash
sudo nano /etc/systemd/system/tomcat.service
```
Paste (adjust `JAVA_HOME` if your path from the previous command differs):
```ini
[Unit]
Description=Apache Tomcat 10
After=network.target

[Service]
Type=forking
User=tomcat
Group=tomcat
Environment="JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64"
Environment="CATALINA_HOME=/opt/tomcat"
Environment="CATALINA_PID=/opt/tomcat/temp/tomcat.pid"
ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh
RestartSec=10
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
Save and exit (`Ctrl+O`, `Enter`, `Ctrl+X` in nano).

Start it:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tomcat
sudo systemctl status tomcat
```

Confirm it's listening locally:
```bash
curl -I http://localhost:8080
```
You should see `HTTP/1.1 200`.

✅ **Checkpoint:** `systemctl status tomcat` shows **active (running)**.

> Want to deploy your own `.war` file through a browser instead of the command line? See **Additional Material: `additional-tomcat-manager-setup.md`** — it sets up Tomcat's built-in web admin page (the Manager app) safely, restricted to your own IP.

---

## Part 9 — Open the firewall for HTTP (80) and Tomcat (8080)

1. Console → EC2 → **Security Groups** → `cloud26-sg` → **Edit inbound rules** → **Add rule**.
2. Add:
   - Type **HTTP**, Port 80, Source **Anywhere-IPv4** (`0.0.0.0/0`)
   - Type **Custom TCP**, Port **8080**, Source **Anywhere-IPv4** (`0.0.0.0/0`)
3. **Save rules.**

> Note: port 8080 here is plain HTTP served by Tomcat's default connector — not HTTPS/TLS.

4. From your laptop's **web browser** (not the SSH terminal), visit:
```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080
```
You should see the **Apache Tomcat 10** welcome page.

✅ **Checkpoint:** the Tomcat splash page loads in your browser over port 8080.

> **Getting a "connection not secure" / security warning even though you typed `http://`?** Your browser is probably auto-upgrading the request to HTTPS on its own (a setting like Chrome's "Always use secure connections"), then failing because nothing is listening for HTTPS here — this is *not* about the URL or your instance being wrong. Fixes, easiest first:
> 1. Make sure you typed the address out fully, including `http://` and `:8080` — don't click an autocomplete/history suggestion, since browsers tend to suggest `https://` even for sites you only ever visited over `http://`.
> 2. If it still gets upgraded: Chrome/Edge → `chrome://settings/security` (or `edge://settings/privacy`) → turn off **"Always use secure connections."** Firefox: address bar padlock/shield icon on the failed page → disable HTTPS-only mode for this site.
> 3. Try a private/incognito window, which sometimes has a fresh, less aggressive default.
>
> This is a browser setting, not a Tomcat or security-group problem — real HTTPS is out of scope for this session (see the note above).

---

## Wrap-up

Today you built, end-to-end:
- An EC2 instance (Ubuntu 26.04, free tier)
- A security group controlling SSH/HTTP/8080 access
- SSH access from your own laptop
- CLI control of the instance as IAM user `CTO`, governed by a scoped IAM policy
- A MySQL database (private, localhost-only)
- A Tomcat 10 web server (public, port 8080)

**Before you leave:** stop your instance to conserve free-tier hours:
```bash
aws ec2 stop-instances --instance-ids <YOUR_INSTANCE_ID> --profile CTO
```
or via Console: Instances → select your instance → **Instance state** → **Stop instance**.

**Do not terminate** your instance unless told to — we may reuse it in a future session.

---

## Want more? Additional material

### Deploy web apps through a browser (Tomcat Manager)

Want to deploy your own `.war` file without `curl`/SSH file transfers? See:

📄 **`additional-tomcat-manager-setup.md`** — sets up a Tomcat admin user and safely opens the built-in Manager web page (restricted to your own IP) so you can upload and deploy `.war` files directly. ~20–30 minutes, optional.

---

### Manage your database with a visual tool (MySQL Workbench)

Prefer a GUI over `sudo mysql` on the command line? See:

📄 **`additional-mysql-workbench-setup.md`** — connects MySQL Workbench (a free desktop app) to your database via an SSH tunnel, so MySQL's `localhost`-only setup from the core lab never has to change. ~20–30 minutes, optional.

---

### Build and deploy your own Java web app (Maven)

Want to build something to deploy instead of using Tomcat's default splash page? See:

📄 **`hello-world-webapp/hello-world-webapp-student-lab.md`** — a minimal Maven-based Jakarta Servlet project (`Hello, world!`), covering the build and two deployment methods side by side: `scp`/SSH and the Tomcat Manager app. A separate follow-on project (`labs/s3-webapp/`), built on these same basics, is covered later in the S3 + AWS SDK for Java session.
