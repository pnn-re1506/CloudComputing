# Lab: Building and Deploying a Java Web App with Maven

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| WAR | Web Application Archive (the packaged `.war` file format Tomcat deploys) |
| JDK | Java Development Kit |
| API | Application Programming Interface |
| SSH | Secure Shell (protocol for remote terminal access) |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| S3 | Simple Storage Service (AWS's object storage service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |

**Prerequisite:** completion of the main EC2 lab (`labs/ec2-session-student-lab.md`) — you need a running instance with Tomcat 10 already installed and serving on port 8080.
**You will need:** JDK 21+ and Maven installed on your own laptop, plus your `cloud26-key.pem` SSH key.

By the end of this lab you will have built a Java web application with Maven, and deployed it to your EC2 instance's Tomcat **two different ways** — once over SSH, once through the Tomcat Manager web page — so you can compare them directly.

> **Note:** this lab covers the Hello World project only. A follow-on lab builds a separate project on the same Maven/servlet foundations — see "Coming later" at the end of this document.

---

## Part 1 — The project: a Maven-based Servlet web app

The project lives in `labs/hello-world-webapp/`. It's a minimal **Jakarta Servlet** application — Tomcat 10.1's servlet standard — that responds `Hello, world!` at `/hello`, plus a small landing page.

```
hello-world-webapp/
├── pom.xml                                              # Maven build configuration
└── src/main/
    ├── java/edu/cloudcomputing26/hello/
    │   └── HelloWorldServlet.java                       # the servlet itself
    └── webapp/
        └── index.html                                   # static landing page
```

Take a quick look at `HelloWorldServlet.java`. Notice:
- It's registered with the `@WebServlet(urlPatterns = {"/hello"})` annotation — no `web.xml` deployment descriptor needed. This is the modern, standard way to map a servlet to a URL.
- It extends `HttpServlet` and overrides `doGet(...)` — the method Tomcat calls for an HTTP GET request.
- Its imports start with `jakarta.servlet.*`, **not** `javax.servlet.*`. This matters: Tomcat 9 and earlier used `javax.servlet`; Tomcat 10 switched to `jakarta.servlet` as part of the Jakarta EE rebrand. Code you find online for "Java servlets" often still shows the old `javax` imports — that will **not** work on this course's Tomcat 10. If you ever copy servlet code from an older tutorial, check this first.

Take a look at `pom.xml` too. The one dependency that matters is:
```xml
<dependency>
  <groupId>jakarta.servlet</groupId>
  <artifactId>jakarta.servlet-api</artifactId>
  <version>6.0.0</version>
  <scope>provided</scope>
</dependency>
```
`scope=provided` means: needed to *compile* against, but Tomcat already supplies the real implementation at runtime, so Maven does **not** bundle it into the `.war`. If this were a normal dependency instead, the servlet API jar would end up duplicated inside your `.war` and could conflict with Tomcat's own copy.

---

## Part 2 — Build it

On your own laptop, check your tools first:
```bash
java -version
mvn -version
```
You need JDK 21 or newer, and any recent Maven 3.x.

From inside the `hello-world-webapp/` folder:
```bash
mvn clean package
```

Maven will download the `jakarta.servlet-api` dependency the first time (scope `provided`, so it's used only to compile, not bundled), compile `HelloWorldServlet.java`, and package everything into a `.war` file.

You should see `BUILD SUCCESS` near the end of the output.

✅ **Checkpoint:** `target/hello-world-webapp.war` exists after the build.
```bash
ls -la target/hello-world-webapp.war
```

---

## Part 3 — Deploy it over SSH

This mirrors exactly how you'd deploy any file to your instance — the same `scp`/`ssh` pattern from the core lab, just with a `.war` file this time.

**Step 1 — Copy the `.war` to the instance:**
```bash
scp -i ~/keys/cloud26-key.pem target/hello-world-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
```

**Step 2 — SSH in, then move the file into Tomcat's `webapps/` folder with the right owner:**
```bash
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
```
Once connected:
```bash
sudo cp /tmp/hello-world-webapp.war /opt/tomcat/webapps/
sudo chown tomcat: /opt/tomcat/webapps/hello-world-webapp.war
```

Tomcat watches its `webapps/` folder and **auto-deploys** any new `.war` it finds within a few seconds — no restart needed. The context path matches the filename: `hello-world-webapp.war` deploys to `/hello-world-webapp`.

**Step 3 — Verify from your laptop's browser (or `curl`):**
```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/hello
```
The first shows the landing page; the second returns the plain-text `Hello, world!` response.

✅ **Checkpoint:** visiting `.../hello-world-webapp/hello` shows `Hello, world!`.

> Getting a browser security warning on this plain `http://` URL? That's your browser auto-upgrading the request to HTTPS on its own, not a real problem — see the core lab's Part 9 note (or Appendix B in the instructor guide) for the fix.

**Clean up the temp copy** (optional, but tidy):
```bash
rm /tmp/hello-world-webapp.war    # while still SSH'd in
```

---

## Part 4 — Deploy it again, this time through the Tomcat Manager

Now deploy the **same `.war` file** a second way: through Tomcat's browser-based admin page, the Manager app — no SSH needed for the deploy step itself.

**Prerequisite:** the Manager app must already be set up on your instance. If you haven't done this yet, follow **`labs/additional-tomcat-manager-setup.md`** first — it walks through:
1. Creating a Manager app user (`tomcat-users.xml`).
2. Restricting access to your own laptop's IP (`RemoteCIDRValve` in `manager.xml`) — this is a real security control, not a formality, so don't skip it or open it to everyone.
3. Confirming you can log in at `http://<YOUR_INSTANCE_PUBLIC_IP>:8080/manager/html`.

Once you're logged into the Manager app:

1. Scroll to the **Deploy** section → **WAR file to deploy**.
2. Click **Choose File**, select `target/hello-world-webapp.war` from your laptop.
3. Click **Deploy**.
4. The app appears in the applications table above.

> If you deployed via SSH in Part 3 and it's still running, the Manager's deploy form will either update it in place or you may see it listed twice depending on your Tomcat version's behavior — if so, use the **Undeploy** link next to the SSH-deployed copy first, then redeploy through the Manager, so you're clearly testing the Manager path on its own.

**Verify the same way as before:**
```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/hello
```

✅ **Checkpoint:** the Manager app's table shows `hello-world-webapp` as running, and the URL above still returns `Hello, world!`.

**To undeploy from the Manager:** click the **Undeploy** link next to the app in the table — this removes both the `.war` and its extracted files in one step, no SSH required.

---

## Part 5 — Compare the two methods

| | SSH / scp | Tomcat Manager |
|---|---|---|
| Tools needed | `scp`, `ssh` | Just a browser |
| Access required | SSH key, security group allows your IP on port 22 | Manager user/password, `RemoteCIDRValve` allows your IP on port 8080 |
| Good for | Scripting, automation, CI/CD pipelines | Quick manual deploys, demos, no terminal needed |
| Undeploy | Manually delete the `.war` and extracted folder, or let a Tomcat restart clear it | One click in the Manager's table |
| Security surface | Reuses your existing, already-hardened SSH access | A second, separate admin surface — must be independently restricted |

Both end up doing the same thing: dropping a `.war` file where Tomcat can find it. The difference is entirely about *how* the file gets there and *who* can do it.

---

## Cleanup

When you're done experimenting, undeploy the app so it's not left running unnecessarily:

- **Via SSH:**
  ```bash
  sudo rm -rf /opt/tomcat/webapps/hello-world-webapp /opt/tomcat/webapps/hello-world-webapp.war
  ```
- **Via the Manager app:** click **Undeploy** next to it in the applications table.

Either way, Tomcat picks up the change automatically — confirm with the same `curl` check from Part 3; you should get a `404` once it's gone.

---

## Coming later

This handout covers the Maven build and both deployment methods for the Hello World servlet — this project (`hello-world-webapp/`) never grows beyond that.

A **separate** project, `labs/s3-webapp/`, adds three S3-backed endpoints (list/upload/download using the AWS SDK for Java) — covered in its own dedicated lab, since it needs extra AWS setup (a bucket, an IAM role) that doesn't belong in this handout: see **`labs/s3-session-student-lab.md`**. It's built starting from the same Maven/servlet basics you just learned here, but lives in its own folder with its own `pom.xml` — you won't be adding to today's project directly.
