# Lab: S3 SDK Walkthrough — Build, Deploy, Test

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| SDK | Software Development Kit |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| CLI | Command Line Interface |
| JDK | Java Development Kit |

**Duration:** ~1 hour
**You will need:** everything from the S3 lab already set up (S3 bucket created, IAM role created and attached to your EC2 instance), plus JDK 21+ and Maven installed on your laptop (from the Java Web App with Maven lab).
**Region:** `ap-southeast-1` (Asia Pacific — Singapore), same as every prior lab.

This lab picks up directly from the S3 lab: you already created a bucket and attached an IAM role to your EC2 instance, but nothing has used that role yet. By the end of this lab you will have built a **new** Maven web app (`s3-webapp/`, separate from `hello-world-webapp/`) with three endpoints that read and write S3 objects using the AWS SDK for Java — authenticated through that role, with no AWS access keys anywhere in your code.

---

## Quick review before starting

A short recap of the two earlier labs, since some time may have passed:

**From the Java Web App with Maven lab:**
- A Maven project (`hello-world-webapp/`) with a single Jakarta Servlet, registered via `@WebServlet`, no `web.xml`.
- `jakarta.servlet.*` imports, not the older `javax.servlet.*` (Tomcat 10+).
- `scope=provided`: needed to compile against, but Tomcat supplies the real implementation at runtime.

**From the S3 lab:**
- S3 core concepts: bucket, object/key, region, Block Public Access (left on).
- Used your bucket from the Console and the AWS CLI, with a least-privilege `CTO` policy scoped to that one bucket.
- Created a second least-privilege policy and an **IAM role** — a different kind of identity than your `CTO` user: a role is assumed by the EC2 instance itself, with short-lived, auto-rotated credentials and no access keys anywhere, then attached that role to your instance.

**Today's one-sentence bridge:** you set up a bucket and a role last time — today you write the code that actually uses them.

✅ **Before continuing, confirm you still have:** your S3 bucket (check the Console), and the IAM role attached to your running EC2 instance (re-run the `curl` check from the S3 lab if unsure — see that lab's Part 5).

---

## Part 1 — Look at the new S3 code

Open `labs/s3-webapp/` — a **new** project, separate from `hello-world-webapp/` (the Java Web App with Maven lab's project). It builds on the same Maven/servlet basics you already know, but lives in its own folder with its own `pom.xml`, since it adds the AWS SDK for Java as a real dependency that `hello-world-webapp/` deliberately never needed. Three servlet files:

- **`S3ListServlet.java`** (`/s3/list`) — lists every object in the bucket.
- **`S3UploadServlet.java`** (`/s3/upload`) — shows an upload form, then saves the submitted file to S3.
- **`S3DownloadServlet.java`** (`/s3/download?key=...`) — streams one object back to your browser.

And a shared helper: **`S3Config.java`**. Open this file first — it's short, and it's the key to how everything else works.

```java
private static final S3Client CLIENT = S3Client.builder()
        .region(REGION)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
```

Notice there's exactly **one** `S3Client`, built once and reused by all three servlets — a small example of the Singleton pattern. And notice *how* that one `S3Client` is built: `.builder()` ... `.build()`, with configuration methods chained in between — that's the Builder pattern. See the two Appendices at the end of this lab if you want the precise explanation of each.

Notice: **no access key, no secret key, anywhere.** There's no `.credentialsProvider(...)` call at all. Without one, the SDK automatically looks for credentials in a series of standard places — and on this EC2 instance, with the role you attached last time, it finds them via the instance's own metadata service. That's the entire reason you created that role before any of this code existed: your code never has to know or store a secret.

**Before building, edit one line** in `S3Config.java`:
```java
static final String BUCKET_NAME = "cloud26-yourname-bucket";
```
Replace this with your actual bucket name from the S3 lab.

---

## Part 2 — Build, deploy, and test

**Build** (from inside `labs/s3-webapp/`):
```bash
mvn clean package
```
✅ **Checkpoint:** `BUILD SUCCESS`, and `target/s3-webapp.war` exists.

**Deploy** (same as the Java Web App lab — either method works):
```bash
scp -i ~/keys/cloud26-key.pem target/s3-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
sudo cp /tmp/s3-webapp.war /opt/tomcat/webapps/
sudo chown tomcat: /opt/tomcat/webapps/s3-webapp.war
```

**Test each endpoint** from your browser:
```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-webapp/s3/list
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-webapp/s3/upload
```
1. Visit `/s3/list` — should show `Object count: 0` on a fresh bucket.
2. Visit `/s3/upload`, choose a file, click **Upload**.
3. Visit `/s3/list` again — your uploaded file should now appear.
4. Visit `/s3/download?key=<the filename you uploaded>` — should show the file content directly.

✅ **Checkpoint:** a file you upload through `/s3/upload` shows up in `/s3/list` and downloads correctly via `/s3/download`.

**If it doesn't work, check in this order:**
1. Did you actually edit `BUCKET_NAME` in `S3Config.java` to your real bucket name (no typos)?
2. Is the IAM role from the S3 lab really attached to **this** instance? (It should still be, even after a stop/start — roles persist — but double check.)
3. Did you replace `<YOUR_BUCKET_NAME>` in **both** places in the policy JSON, back in the S3 lab?
4. Is your bucket really in `ap-southeast-1`?
5. Did you rebuild (`mvn clean package`) and redeploy after editing the bucket name — not just redeploy an old `.war`?

---

## Wrap-up

Across the Java Web App, S3, and this lab, you built, end-to-end:
- An EC2 instance, running Tomcat
- An S3 bucket, used from the Console, the CLI, and now your own code
- **Two** least-privilege S3 policies, scoped to that one bucket, for two deliberately separate identities (your `CTO` user, and an IAM role)
- Three servlet endpoints using the AWS SDK for Java, authenticated with **zero hardcoded credentials**

**Before you leave:**
- Clean up test objects if you like: `aws s3 rm s3://cloud26-yourname-bucket/somefile.txt --profile CTO`
- Stop your EC2 instance as usual.
- **Don't delete your bucket** — you may reuse it in a future session.

---

## Appendix: the Singleton pattern in `S3Config.java`

If you've seen design patterns before, you might be tempted to call `S3Config` itself "a Singleton" — but the precise answer is slightly different, and worth getting right.

`S3Config` is never actually instantiated. Its constructor is `private S3Config() {}`, which exists only to stop anyone from writing `new S3Config()` — nothing inside the class ever calls it either. So `S3Config` isn't a singleton in the textbook sense of "a class with exactly one instance of itself." It's a **static utility/holder class** instead — just a namespace for shared constants and a shared object.

What *is* a singleton is the `S3Client` it holds:

```java
private static final S3Client CLIENT = S3Client.builder()
        .region(REGION)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();

static S3Client client() {
    return CLIENT;
}
```

- `private static final S3Client CLIENT` — exactly **one** `S3Client` object exists, created once, the moment `S3Config` is first loaded — not a new one per request.
- `static S3Client client()` — the one place every servlet goes to get it, instead of each servlet building its own.

This shape — a separate class holding the one shared instance of some *other* type — is sometimes called the "static holder" idiom. It's an extremely common pattern in real code, and it's why `S3Config` and `S3Client` together implement Singleton, even though `S3Config` itself isn't the singleton.

**Why this matters, not just as a naming exercise:** an `S3Client` manages real resources under the hood, including an HTTP connection pool. Building a new one on every request would mean repeatedly paying that setup cost and throwing away connections that could have been reused. One shared, long-lived client, reused across every request, is both the efficient choice and the correct one — which is exactly what this pattern gives you here.

---

## Appendix: the Builder pattern in `S3Client.builder()`

The way that one `S3Client` singleton gets *constructed* is its own well-known pattern — the **Builder pattern**:

```java
private static final S3Client CLIENT = S3Client.builder()
        .region(REGION)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
```

Instead of one constructor call that takes every parameter at once — awkward once there are many optional settings, and easy to get wrong by passing them in the wrong order — you first get a **builder object**. The builder has one method per configurable property, and each of those methods **returns the builder itself**, so you can chain calls one after another. A final `.build()` call then checks everything and hands back the actual, finished object.

Walking through this specific call:
- `S3Client.builder()` — there's no public constructor for `S3Client` you can call directly; this **static factory method** is the only way in, and it returns a builder, not an `S3Client` yet.
- `.region(REGION)` — sets the region, and returns the same builder so you can keep chaining. This chained, "reads almost like a sentence" style is called a **fluent interface**, and it's the hallmark of a builder in practice.
- `.httpClientBuilder(UrlConnectionHttpClient.builder())` — sets the HTTP client. Notice the argument is *itself* a builder (`UrlConnectionHttpClient.builder()`, with no `.build()` on it) — `S3Client`'s builder wants the *builder* for the HTTP client, not an already-finished one, so it can finish configuring and building it internally as part of its own `.build()` call. Two builders, nested.
- `.build()` — the final call. It checks everything configured so far (is there a region? can credentials be resolved?) and returns the real, ready-to-use `S3Client`. After this, the client's configuration can't change — it's effectively locked in.

**Why use a builder instead of a plain constructor here:** `S3Client` has many optional settings — region, HTTP client, credentials, retry policy, timeouts, and more — and most code only needs to set one or two of them, letting sensible defaults handle the rest. A single constructor would force you to specify (or guess at) every parameter, in a fixed order, even the ones you don't care about. A builder lets you set only what matters, by name, in whatever order you like — which is exactly what's happening above: only `region` and `httpClientBuilder` are set, and everything else quietly falls back to the SDK's defaults.
