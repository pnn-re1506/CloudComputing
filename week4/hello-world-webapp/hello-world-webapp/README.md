# Hello World Servlet WebApp

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| WAR | Web Application Archive (the packaged `.war` file format Tomcat deploys) |
| JDK | Java Development Kit |
| API | Application Programming Interface |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| SSH | Secure Shell (protocol for remote terminal access) |
| S3 | Simple Storage Service (AWS's object storage service) |
| SDK | Software Development Kit |
| IAM | Identity and Access Management (AWS's user/permissions service) |

## What this is

A Java web application built with **Maven** using the **Jakarta Servlet API** (the servlet standard used by Tomcat 10.1, this course's Tomcat version). A single `Hello, world!` servlet — nothing more.

This project is a **reference/teaching example**, meant to be built on your own laptop and deployed to the EC2 instance's Tomcat via the Manager app (`labs/additional-tomcat-manager-setup.md`) or by copying the `.war` file over SSH.

It's covered in `labs/java-web-app-session-student-lab.md`.

**This project has a follow-on:** the sibling folder `labs/s3-webapp/` is a separate project — its own folder, its own `pom.xml` — with three S3-backed endpoints using the AWS SDK for Java v2, covered in the course's separate S3 + SDK session. It's a distinct project on purpose, not an extension of this one: at the point this lab is taught, students haven't set up an S3 bucket or an IAM role yet, and this project has no AWS SDK dependency at all — building or deploying it never touches AWS beyond the EC2 instance and Tomcat already in place.

## Project layout

```
hello-world-webapp/
├── pom.xml                                              # Maven build configuration
└── src/main/
    ├── java/edu/cloudcomputing26/hello/
    │   └── HelloWorldServlet.java                       # /hello — the Hello World servlet
    └── webapp/
        └── index.html                                   # static landing page, links to the endpoint above
```

No `web.xml` deployment descriptor is included — the servlet is registered with the `@WebServlet` annotation instead (the standard approach since Servlet 3.0, still current under Jakarta Servlet 6.0).

## Prerequisites

- **JDK 21** (or newer) installed on your laptop — check with `java -version`.
- **Maven** installed — check with `mvn -version`. ([Download](https://maven.apache.org/download.cgi) if you don't have it.)

## Build it

From this folder:

```bash
mvn clean package
```

This produces `target/hello-world-webapp.war`. Maven downloads the `jakarta.servlet-api` dependency automatically the first time you build (scope `provided` — it's needed to *compile* against, but Tomcat supplies the real implementation at runtime, so it is **not** bundled into the `.war`). No other dependencies — this build never touches the network beyond that one download.

✅ **Checkpoint:** `target/hello-world-webapp.war` exists after the build.

## Deploy it to your EC2 instance's Tomcat

Two options, both already covered by other course material:

1. **Through the browser (Tomcat Manager app)** — see `labs/additional-tomcat-manager-setup.md`. Upload `target/hello-world-webapp.war` directly through the Manager's **Deploy** form.
2. **Over SSH** — copy the file to the instance and drop it into Tomcat's `webapps/` folder:
   ```bash
   scp -i ~/keys/cloud26-key.pem target/hello-world-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
   ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
   sudo cp /tmp/hello-world-webapp.war /opt/tomcat/webapps/
   sudo chown tomcat: /opt/tomcat/webapps/hello-world-webapp.war
   ```
   Tomcat auto-deploys any `.war` dropped into `webapps/` within a few seconds — no restart needed.

Either way, the context path matches the `.war` filename: `hello-world-webapp.war` deploys to `/hello-world-webapp`.

## Try it

Visit, from your laptop's browser or with `curl`:

```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/hello
```

The first shows the landing page; the second returns the plain-text `Hello, world!` response.

✅ **Checkpoint:** `curl http://<YOUR_INSTANCE_PUBLIC_IP>:8080/hello-world-webapp/hello` prints `Hello, world!`.

## Notes for the instructor

- **Verified working:** built with `mvn clean package` and deployed to a live Tomcat 10.1.19 instance during preparation — compiled, packaged, deployed, and responded correctly with `HTTP/1.1 200` and the expected body. Not just reviewed, actually run.
- **`jakarta.servlet` vs `javax.servlet`:** this is the single most common mistake here. Tomcat 9 and earlier use the `javax.servlet.*` package; Tomcat 10+ switched to `jakarta.servlet.*` as part of the Jakarta EE rebrand. Code or tutorials found online for "Java servlets" often still show the old `javax` imports, which **will not deploy** on this course's Tomcat 10. Worth calling out explicitly if students search for extra examples.
- **`scope=provided`** on the servlet-api dependency is worth a quick explanation: it means "needed to compile against, but the container already has it at runtime" — the opposite of a normal dependency. Bundling the servlet API jar into the WAR instead (e.g. forgetting the scope) can cause classloading conflicts with Tomcat's own copy.
- **Good next step for students:** modify `doGet` to read a query parameter (`request.getParameter("name")`) and personalize the greeting — a small extension that touches request handling without adding new concepts.
- **This project is deliberately kept minimal, with no AWS SDK dependency at all.** The S3-backed follow-on project (`labs/s3-webapp/`) is a separate `pom.xml`/folder for exactly that reason — see its own README for details.
