# S3 SDK WebApp

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| WAR | Web Application Archive (the packaged `.war` file format Tomcat deploys) |
| JDK | Java Development Kit |
| API | Application Programming Interface |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| S3 | Simple Storage Service (AWS's object storage service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SSH | Secure Shell (protocol for remote terminal access) |
| SDK | Software Development Kit |
| BOM | Bill of Materials (a Maven mechanism for pinning a consistent set of library versions) |

## What this is

A Java web application built with **Maven** using the **Jakarta Servlet API** (the servlet standard used by Tomcat 10.1, this course's Tomcat version), with three servlet endpoints backed by **S3**, using the **AWS SDK for Java v2**.

This project is a **reference/teaching example**, meant to be built on your own laptop and deployed to the EC2 instance's Tomcat via the Manager app (`labs/additional-tomcat-manager-setup.md`) or by copying the `.war` file over SSH.

**This is the follow-on to `labs/hello-world-webapp/`** — the earlier Java Web App with Maven session's project. It's a separate folder and a separate `pom.xml` on purpose: it adds the AWS SDK for Java as a real dependency (several megabytes bundled into the `.war`), which the earlier session's project deliberately never needed. Students build this project fresh in the S3 session, after already having built the simpler one once.

The S3 endpoints (`S3Config`, `S3ListServlet`, `S3UploadServlet`, `S3DownloadServlet`) are covered in `labs/s3-session-student-lab.md` and `lectures/s3-session-instructor-guide.md`. **Building this project alone is not enough to use them** — they also need an S3 bucket and an IAM role attached to the EC2 instance, both set up in that same session.

## Project layout

```
s3-webapp/
├── pom.xml                                              # Maven build configuration
└── src/main/
    ├── java/edu/cloudcomputing26/hello/
    │   ├── S3Config.java                                 # shared S3Client — no credentials in code
    │   ├── S3ListServlet.java                            # /s3/list — list bucket contents
    │   ├── S3UploadServlet.java                          # /s3/upload — upload a file
    │   └── S3DownloadServlet.java                        # /s3/download?key=... — download an object
    └── webapp/
        └── index.html                                   # static landing page, links to every endpoint above
```

No `web.xml` deployment descriptor is included — every servlet is registered with the `@WebServlet` annotation instead (the standard approach since Servlet 3.0, still current under Jakarta Servlet 6.0).

## Prerequisites

- **JDK 21** (or newer) installed on your laptop — check with `java -version`.
- **Maven** installed — check with `mvn -version`. ([Download](https://maven.apache.org/download.cgi) if you don't have it.)
- An S3 bucket and an IAM role attached to your EC2 instance — see `labs/s3-session-student-lab.md` Parts 2–5. Without these, the project still *builds*, but the endpoints return errors when you actually visit them.

## Build it

**Before building, edit one line** in `S3Config.java`:
```java
static final String BUCKET_NAME = "cloud26-yourname-bucket";
```
Replace this with your own bucket name.

From this folder:

```bash
mvn clean package
```

This produces `target/s3-webapp.war`. Maven downloads the `jakarta.servlet-api` and AWS SDK for Java (via its BOM) dependencies automatically the first time you build.

✅ **Checkpoint:** `target/s3-webapp.war` exists after the build.

## Deploy it to your EC2 instance's Tomcat

Two options, both already covered by other course material:

1. **Through the browser (Tomcat Manager app)** — see `labs/additional-tomcat-manager-setup.md`. Upload `target/s3-webapp.war` directly through the Manager's **Deploy** form.
2. **Over SSH** — copy the file to the instance and drop it into Tomcat's `webapps/` folder:
   ```bash
   scp -i ~/keys/cloud26-key.pem target/s3-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
   ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
   sudo cp /tmp/s3-webapp.war /opt/tomcat/webapps/
   sudo chown tomcat: /opt/tomcat/webapps/s3-webapp.war
   ```
   Tomcat auto-deploys any `.war` dropped into `webapps/` within a few seconds — no restart needed.

Either way, the context path matches the `.war` filename: `s3-webapp.war` deploys to `/s3-webapp`.

## Try it

Visit, from your laptop's browser:

```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-webapp/
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-webapp/s3/list
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-webapp/s3/upload
```

✅ **Checkpoint:** a file you upload through `/s3/upload` shows up in `/s3/list` and downloads correctly via `/s3/download`.

**This won't work yet** without the S3 bucket and IAM role setup from `labs/s3-session-student-lab.md` — that lab covers the AWS-side setup this project depends on.

## Notes for the instructor

- **Verified by compile/package only, not a live AWS deployment** — see `lectures/s3-session-instructor-guide.md` for the details of what that session covers and its own verification notes.
- **`scope=provided` vs. default scope:** the Jakarta Servlet API dependency uses `scope=provided` (Tomcat already has it at runtime, so Maven doesn't bundle it). The AWS SDK dependency is the opposite case — Tomcat has no idea what S3 is, so it uses the default (bundled) scope instead. Worth contrasting the two explicitly if this is the first time students see `scope=provided` used deliberately (they'll have seen it once already, in `hello-world-webapp/`, without the contrast to compare it against).
- **Why a separate project, not an extension of `hello-world-webapp/`:** keeping the two `pom.xml` files separate means the earlier session's project never gains a multi-megabyte AWS SDK dependency it doesn't need, and students in the earlier session never see S3 endpoints they haven't been taught yet. See `labs/hello-world-webapp/README.md` for the other side of this split.
