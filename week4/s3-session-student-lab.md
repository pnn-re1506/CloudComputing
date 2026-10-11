# Lab: Amazon S3 and the AWS SDK for Java

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| SDK | Software Development Kit |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| CLI | Command Line Interface |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| SSH | Secure Shell (protocol for remote terminal access) |
| JSON | JavaScript Object Notation |

**Duration:** 3 hours
**You will need:** everything from the EC2 lab already set up (running instance, Tomcat, `CTO` credentials), plus JDK 21+ and Maven installed on your laptop (from the `hello-world-webapp` lab — you don't need that project itself, just the tools).
**Region:** `ap-southeast-1` (Asia Pacific — Singapore), same as every prior lab — your S3 bucket must be created here too.

By the end of this lab you will have created an S3 bucket, used it from the Console and the CLI, set up an IAM role for your EC2 instance, and built a **new** Maven web app (`s3-webapp/`, separate from `hello-world-webapp/`) with three endpoints that read and write S3 objects using the AWS SDK for Java — with no AWS access keys anywhere in your code.

---

## Part 1 — Introduction to S3

S3 (Simple Storage Service) is **object storage** — not a filesystem, not a database. You store and fetch whole files ("objects") by name ("key"); there's no folder structure underneath, even though the Console displays things as if there were.

| Term | Meaning |
|---|---|
| **Bucket** | A top-level container for objects. Bucket names are **globally unique across all of AWS** — not just your account. |
| **Object** | A file stored in a bucket, identified by a **key** (e.g. `photos/hello.jpg`) |
| **Region** | Where a bucket lives — chosen at creation, can't be changed afterward |
| **Block Public Access** | An account/bucket setting, **on by default** — buckets are private unless you deliberately turn this off. Leave it on for this course. |

---

## Part 2 — Create a bucket, and try uploading/viewing/deleting an object

1. Console → search **S3** → **Create bucket**.
2. **Bucket name:** pick something you're confident is unique, e.g. `cloud26-<your-name>-bucket`. If you get "bucket name already exists," try again with something more specific.
3. **AWS Region:** **Asia Pacific (Singapore) `ap-southeast-1`** — this must match, or your code later in this lab won't find the bucket without extra work.
4. Leave **Block Public Access settings** with all four boxes checked (the default).
5. Leave everything else at default.
6. **Create bucket.**

✅ **Checkpoint:** the bucket appears in your bucket list, region shows `ap-southeast-1`.

**Try uploading, viewing, and deleting an object:**
1. Click into the bucket → **Upload** → **Add files** → pick any small file → **Upload**.
2. Click the object's name → note the **Object URL**. Clicking that URL should give you **Access Denied** — this is expected: the object exists, but Block Public Access means it isn't reachable by anyone with just the link.
3. Delete the object: check its box → **Delete** → confirm.

---

## Part 3 — S3 via the AWS CLI

**First, try it — this should fail:**
```bash
aws s3 ls --profile CTO
```
You should see `AccessDenied`. Your `CTO` user's policy from the EC2 session only covers EC2 — it grants nothing for S3. Same lesson as before: a new service means a new policy.

**Create and attach a least-privilege S3 policy for `CTO`:**

1. Console (sign in as admin) → IAM → Policies → **Create policy** → JSON tab. Paste, replacing **both** occurrences of `<YOUR_BUCKET_NAME>` with your actual bucket name:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListAllBucketsAlwaysAllowed",
      "Effect": "Allow",
      "Action": "s3:ListAllMyBuckets",
      "Resource": "*"
    },
    {
      "Sid": "ListSpecificBucket",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<YOUR_BUCKET_NAME>"
    },
    {
      "Sid": "ReadWriteDeleteObjectsInSpecificBucket",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::<YOUR_BUCKET_NAME>/*"
    }
  ]
}
```

A quick note on why this has three separate permission blocks: `s3:ListAllMyBuckets` (listing every bucket you own) can't be scoped to just one bucket — it's inherently account-wide, so it uses `Resource: "*"`. `s3:ListBucket` (singular — listing the *contents* of one bucket) is a different action that *can* be scoped, and is. `GetObject`/`PutObject`/`DeleteObject` cover uploading, downloading, and deleting objects.

2. Name the policy `S3-<your-bucket-name>-CTO-CLI`, create it.
3. IAM → Users → `CTO` → **Add permissions** → **Attach policies directly** → select the new policy → Add.

**Retry:**
```bash
aws s3 ls --profile CTO
```
✅ **Checkpoint:** this now works instead of failing with `AccessDenied`.

**Now try the full set of commands:**
```bash
# List objects in your bucket (should be empty right now)
aws s3 ls s3://cloud26-yourname-bucket --profile CTO

# Upload a file
aws s3 cp ./somefile.txt s3://cloud26-yourname-bucket/somefile.txt --profile CTO

# List again — the object should now appear
aws s3 ls s3://cloud26-yourname-bucket --profile CTO

# Download it back down (to a new local filename)
aws s3 cp s3://cloud26-yourname-bucket/somefile.txt ./downloaded.txt --profile CTO

# Delete it
aws s3 rm s3://cloud26-yourname-bucket/somefile.txt --profile CTO
```

✅ **Checkpoint:** a file you upload via the CLI shows up in the Console bucket view, and vice versa.

---

## — Break (10 min) —

---

## Part 4 — Create an IAM role for your EC2 instance

So far you've accessed S3 as **yourself** (`CTO`). Now your servlet, running *on* the EC2 instance, needs its own way in — without you ever putting your access keys on the server. The answer is an **IAM role**: a separate identity that the instance itself can assume, with no password or long-lived keys involved.

**Step 1 — Create the policy:**

1. Console (sign in as admin) → IAM → Policies → **Create policy** → JSON tab. Paste, replacing **both** occurrences of `<YOUR_BUCKET_NAME>` with your actual bucket name:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListSpecificBucket",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<YOUR_BUCKET_NAME>"
    },
    {
      "Sid": "ReadWriteObjectsInSpecificBucket",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::<YOUR_BUCKET_NAME>/*"
    }
  ]
}
```

Note the ARN shape: `arn:aws:s3:::bucket-name` — no region, no account ID (S3 bucket names are already globally unique, so those fields aren't needed). Also note the two different `Resource` shapes: **listing** the bucket uses the bucket ARN itself; **reading/writing objects** needs `/*` appended, meaning "any object inside."

2. Name the policy `S3-<your-bucket-name>-ReadWrite`, create it.

**Step 2 — Create the role:**

1. IAM → Roles → **Create role**.
2. **Trusted entity type:** AWS service.
3. **Use case:** EC2.
4. **Add permissions:** find and check the policy you just created.
5. **Role name:** e.g. `cloud26-s3-instance-role`.
6. **Create role.**

✅ **Checkpoint:** your new role appears in IAM → Roles, with your S3 policy attached.

---

## Part 5 — Attach the role to your EC2 instance

1. Console → EC2 → **Instances** → select your instance → **Actions** → **Security** → **Modify IAM role**.
2. **IAM role:** select the role you just created.
3. **Update IAM role.**

No restart needed — this takes effect immediately.

**Verify from inside your instance** (SSH in):
```bash
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/iam/security-credentials/
```
This should print your role's name.

**Note:** this course's instances enforce **IMDSv2** — the metadata service requires a short-lived token (fetched via the `PUT` request above) before it will answer any `GET` request. A plain `curl` with no token returns nothing (silently, no error text) — it's actually a `401 Unauthorized` under the hood, just not shown with the `-s` flag. If you only ever get blank output, this token step is almost always why.

✅ **Checkpoint:** the command above shows your role name, not an empty response.

---

## Part 6 — Look at the new S3 code

Open `labs/s3-webapp/` — a **new** project, separate from `hello-world-webapp/` (the Java Web App with Maven session's project). It builds on the same Maven/servlet basics you already know, but lives in its own folder with its own `pom.xml`, since it adds the AWS SDK for Java as a real dependency that `hello-world-webapp/` deliberately never needed. Three servlet files:

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

Notice: **no access key, no secret key, anywhere.** There's no `.credentialsProvider(...)` call at all. Without one, the SDK automatically looks for credentials in a series of standard places — and on this EC2 instance, with the role you just attached, it finds them via the instance's own metadata service. That's the entire reason for Parts 4 and 5: your code never has to know or store a secret.

**Before building, edit one line** in `S3Config.java`:
```java
static final String BUCKET_NAME = "cloud26-yourname-bucket";
```
Replace this with your actual bucket name from Part 2.

---

## Part 7 — Build, deploy, and test

**Build** (from inside `labs/s3-webapp/`):
```bash
mvn clean package
```
✅ **Checkpoint:** `BUILD SUCCESS`, and `target/s3-webapp.war` exists.

**Deploy** (same as the EC2 session — either method works):
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
2. Is the IAM role really attached to **this** instance (Part 5)?
3. Did you replace `<YOUR_BUCKET_NAME>` in **both** places in the policy JSON?
4. Is your bucket really in `ap-southeast-1`?
5. Did you rebuild (`mvn clean package`) and redeploy after editing the bucket name — not just redeploy an old `.war`?

---

## Wrap-up

Today you built, end-to-end:
- An S3 bucket, used from the Console, the CLI, and your own code
- **Two** least-privilege S3 policies, scoped to that one bucket, for two deliberately separate identities:
  - One attached to your `CTO` **user** — *who you are*, used for the CLI in Part 3 (includes delete, since you deleted an object from the command line)
  - One attached to a new IAM **role** — *what the server is allowed to do*, assumed automatically by the EC2 instance itself (no delete permission — the servlets never needed it)
- Three servlet endpoints using the AWS SDK for Java, authenticated with **zero hardcoded credentials**

**Before you leave:**
- Clean up test objects if you like: `aws s3 rm s3://cloud26-yourname-bucket/somefile.txt --profile CTO`
- Stop your EC2 instance as usual.
- **Don't delete your bucket** — you may reuse it in a future session.
