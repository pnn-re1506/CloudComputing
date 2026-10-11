# Cloud Computing Course: Session Summary

This document summarizes what was done in the course workspace, week by week.

## Overview

The course builds one continuous project on AWS (region `ap-southeast-1`, Singapore):

1. Week 3: launch a server (EC2) and install MySQL and Tomcat.
2. Week 4: build Java web apps and deploy them to Tomcat; set up S3 and IAM.
3. Week 5: build a custom S3 web app for the assignment.

---

## Week 3: EC2, MySQL, Tomcat

Lab: `week3/ec2-session-student-lab.md`

| Task | Result |
|---|---|
| Launch EC2 instance | Ubuntu 26.04 LTS, free tier, named `cloud26-lab`, key pair `cloud26-key`, security group `cloud26-sg` |
| SSH access | Connected with `ssh -i cloud26-key.pem ubuntu@<IP>` |
| Fix key permissions | Resolved the Windows "UNPROTECTED PRIVATE KEY FILE" error with `icacls` (reset permissions, grant read to own user only, remove inheritance) |
| AWS CLI profile | Configured `aws configure --profile CTO` and verified with `aws sts get-caller-identity` |
| Stop/start via CLI | Learned IAM users have no permissions by default; a scoped policy allows start/stop/describe on one instance. Public IP changes after each stop/start |
| MySQL | Installed `mysql-server`, ran `mysql_secure_installation`, created `cloud26_demo`. Listens on localhost only |
| Tomcat 10 | Installed OpenJDK, created a `tomcat` user, wrote a systemd service, confirmed `HTTP 200` on port 8080 |
| Firewall | Opened ports 80 and 8080 in `cloud26-sg`; the Tomcat welcome page loaded in the browser |

Optional materials in this folder:

- `additional-tomcat-manager-setup.md`: deploy `.war` files through the browser, restricted by IP with `RemoteCIDRValve`.
- `additional-mysql-workbench-setup.md`: connect MySQL Workbench through an SSH tunnel.

### Issues solved

- SSH key permission error on Windows (fixed with `icacls`).
- Understanding the `mysql_secure_installation` prompts (answer `y` to remove anonymous users, disallow remote root, remove test DB, reload privileges).
- `aws sts get-caller-identity` returns the account ID; this is normal and needs no IAM permission.

---

## Week 4: Java web apps and S3

### Lab A: Hello World web app (`java-web-app-session-student-lab.md`)

- Built `hello-world-webapp` with Maven (`mvn clean package`), producing `hello-world-webapp.war`.
- Deployed it over SSH (`scp`, then `cp` and `chown` into `/opt/tomcat/webapps/`).
- Optionally deployed it through the Tomcat Manager app and compared the two methods.
- Key concepts: Jakarta Servlet (`jakarta.*`, not `javax.*`), `@WebServlet`, `scope=provided`.
- Issue solved: `scp` was run inside the SSH session instead of on the laptop. The fix was to run `scp` locally (prompt `PS C:\...`), not on the server (prompt `ubuntu@ip-...`).

### Lab B: S3 and AWS SDK for Java (`s3-session-student-lab.md`)

- Created an S3 bucket in `ap-southeast-1` with Block Public Access left on.
- Used S3 from the Console and the CLI as `CTO`, after attaching a least-privilege policy for that one bucket.
- Created a second least-privilege policy (no delete) and an IAM role for EC2, then attached the role to the instance.
- Verified the role through the IMDSv2 metadata service.
- Built and deployed `s3-webapp` with list, upload and download servlets. The code holds no access keys; the SDK finds credentials through the instance role.
- Key concept: two separate identities. The `CTO` user is used from the laptop CLI. The IAM role is used by code running on EC2.

---

## Week 5: Assignment, S3 web page

Lab: `week5/s3-sdk-walkthrough-session-student-lab.md` (walkthrough of the Week 4 S3 app, plus Singleton and Builder pattern appendices).

### Assignment requirements

1. On load, show the name of the configured bucket.
2. On load, show a table of every object (key, size, last modified).
3. Let the user choose a local file and upload it.
4. After a successful upload, refresh the table without a page reload.

### Solution: `week5/s3-webapp`

| File | Purpose |
|---|---|
| `S3Config.java` | Shared `S3Client` (no credentials in code) and the bucket name |
| `S3ObjectsApiServlet.java` | `GET /api/objects`: returns the bucket name and all objects as JSON (paginated, so more than 1000 objects work) |
| `S3UploadApiServlet.java` | `POST /api/upload`: stores the uploaded file with `PutObject` and returns JSON |
| `Json.java` | Small JSON string escaper (no extra library) |
| `src/main/webapp/index.html` | Single page; uses `fetch` to load the table on page load, upload without reload, and refresh the table after success |

Build result: `mvn clean package` succeeded and produced `target/s3-webapp.war`.

Deploy steps:

```powershell
scp -i <key.pem> target\s3-webapp.war ubuntu@<IP>:/tmp/
ssh -i <key.pem> ubuntu@<IP>
```

```bash
sudo rm -rf /opt/tomcat/webapps/s3-webapp /opt/tomcat/webapps/s3-webapp.war
sudo cp /tmp/s3-webapp.war /opt/tomcat/webapps/
sudo chown tomcat: /opt/tomcat/webapps/s3-webapp.war
```

Then open `http://<IP>:8080/s3-webapp/`.

---

## Security notes

- `cloud26-key.pem` and `phucnhan_accessKeys.csv` are secrets. They are listed in `.gitignore`.
- Both files sit inside OneDrive, which syncs them to the cloud. Move the `.pem` outside OneDrive and delete the access key CSV.
- If a key may have leaked, deactivate it in IAM (Users, `CTO`, Security credentials) and create a new one.
- Never open the Tomcat Manager app or the database port to `0.0.0.0/0`.

## End-of-session checklist

- [ ] Stop the EC2 instance (do not terminate it).
- [ ] Do not delete the S3 bucket.
- [ ] Delete `phucnhan_accessKeys.csv` from OneDrive.
- [ ] Confirm the assignment submission format with the lecturer.
