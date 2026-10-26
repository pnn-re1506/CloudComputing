# S3 SDK WebApp v5

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| JDK | Java Development Kit |
| API | Application Programming Interface |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| S3 | Simple Storage Service (AWS's object storage service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |
| JS | JavaScript |
| URL | Uniform Resource Locator |
| UI | User Interface |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| JSON | JavaScript Object Notation |

## What this is

A Java web application built with **Maven** using the **Jakarta Servlet API**, with four servlet endpoints backed by **S3**, using the **AWS SDK for Java v2** — functionally identical to `labs/s3-v4-webapp/` from the outside, with one change: `AuthConfig` no longer knows what the correct token *is*. It only knows how to ask.

In v3/v4, `AuthConfig.TOKEN` was a hardcoded constant and `isAuthorized` compared against it directly, in this project's own code. In v5, that comparison has moved entirely into a separate AWS Lambda function, **`CheckTokenIsValid`** (see `labs/check-token-is-valid-lambda/`), which holds the correct token as its own environment variable. `AuthConfig.isAuthorized` now **invokes that function** via the SDK's `LambdaClient` and trusts whatever it answers.

This is the working example for the "AWS Lambda" session — see `lectures/aws-lambda-session-instructor-guide.md`.

## Project layout

```
s3-v5-webapp/
├── pom.xml                                              # Maven build config — NEW: adds the SDK's lambda module
└── src/main/
    ├── java/edu/cloudcomputing26/hello/
    │   ├── S3Config.java                                 # shared S3Client — unchanged from v4
    │   ├── AuthConfig.java                                # CHANGED: no local token; invokes CheckTokenIsValid instead
    │   ├── AuthFilter.java                                 # unchanged from v4 — still one line, still before every /s3/*
    │   ├── LoginServlet.java                               # CHANGED: calls AuthConfig.isAuthorized(String) directly
    │   ├── S3ListServlet.java                            # unchanged
    │   ├── S3UploadServlet.java                          # unchanged
    │   ├── S3DownloadServlet.java                        # unchanged
    │   └── S3GetBucketName.java                          # unchanged
    └── webapp/
        └── index.html                                   # unchanged
```

## The authentication mechanism, now split across two deployables

- **`check-token-is-valid-lambda`** (a separate project, separately deployed) — holds the correct token, as its own `TOKEN` environment variable. Answers one question: given a token, is it correct? See that project's README for how to build and deploy it.
- **`AuthConfig.isAuthorized(...)`** (this project) — has two overloads now:
  - `isAuthorized(HttpServletRequest request)` — reads the `Authorization: Bearer <token>` header (unchanged from v3/v4), then delegates to:
  - `isAuthorized(String submittedToken)` — invokes `CheckTokenIsValid` with that token as the payload, and returns `true` only if Lambda's response is exactly `true`.
- **`AuthFilter`** — **unchanged** from v4, still calls `AuthConfig.isAuthorized(request)`. It has no idea the implementation now makes a network call to Lambda instead of comparing two strings in memory.
- **`LoginServlet`** — now calls `AuthConfig.isAuthorized(submittedToken)` (the `String` overload) directly, instead of comparing against a `TOKEN` constant that no longer exists in this project.
- **There is still no server-side session**, and the browser side (`index.html`) is unchanged — the token is still kept in a plain JS variable, never `localStorage`.

**What never exists in this project, on purpose:** the correct token's actual value. Reading every file in `s3-v5-webapp/` end to end will not reveal it — only the deployed Lambda function's configuration does.

## Prerequisites

- Everything `s3-v4-webapp` needed, **plus:**
- **The `CheckTokenIsValid` Lambda function already created and configured** — see `labs/check-token-is-valid-lambda/README.md`. Test it directly in the Lambda console first (that README's checkpoint) before wiring this project up to it — if the function itself doesn't work yet, every request through this app will fail with no way to tell why from this side.
- **The EC2 instance's IAM role updated to allow `lambda:InvokeFunction`** — see the next section. Without this, every `AuthConfig.isAuthorized` call throws (caught as a non-`true` response below, so the net effect is everyone gets a `401`, with the real cause only visible in Tomcat's logs).

## Updating the IAM role: `lambda:InvokeFunction`

The instance role from the S3 session only grants S3 actions — it has no idea Lambda exists. A new, narrowly-scoped statement is needed, continuing the least-privilege pattern from the "Security of vs. in the Cloud" session: grant exactly the one action, on exactly the one function, nothing broader.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "InvokeCheckTokenIsValidOnly",
      "Effect": "Allow",
      "Action": "lambda:InvokeFunction",
      "Resource": "arn:aws:lambda:ap-southeast-1:<ACCOUNT_ID>:function:CheckTokenIsValid"
    }
  ]
}
```

Attach this as an additional policy on the **same IAM role** already attached to the EC2 instance (IAM console → Roles → your instance role → Add permissions → Create inline policy → JSON). **Do not** grant `lambda:*` or `Resource: "*"` — the point of this statement, same as the EC2 start/stop policy from earlier in the course, is that the instance can invoke this one function and nothing else: not `CreateFunction`, not `UpdateFunctionConfiguration` (which could rewrite the `TOKEN` environment variable itself!), not any other function in the account.

✅ **Checkpoint — confirm the role change took effect, from the EC2 instance itself, before touching this webapp at all:**
```bash
aws lambda invoke --function-name CheckTokenIsValid --payload '"claude26"' --cli-binary-format raw-in-base64-out /tmp/out.json && cat /tmp/out.json
```
Should print `true` (or `false` for a wrong token) with no `AccessDenied` error. If this fails, fix it here first — the webapp's own errors will be harder to diagnose.

## Build it

**Before building, edit:**

In `S3Config.java`:
```java
static final String BUCKET_NAME = "cloud26-yourname-bucket";
```

In `AuthConfig.java`:
```java
private static final String FUNCTION_NAME = "CheckTokenIsValid";
```
(only if you named your function differently — otherwise leave as-is). **There is no token to edit here anymore** — that lives solely in the Lambda function's `TOKEN` environment variable.

From this folder:

```bash
mvn clean package
```

✅ **Checkpoint:** `target/s3-v5-webapp.war` exists after the build.

## Deploy it to your EC2 instance's Tomcat

```bash
scp -i ~/keys/cloud26-key.pem target/s3-v5-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
sudo cp /tmp/s3-v5-webapp.war /opt/tomcat/webapps/
sudo chown tomcat: /opt/tomcat/webapps/s3-v5-webapp.war
```

The context path matches the `.war` filename: `s3-v5-webapp.war` deploys to `/s3-v5-webapp`.

## Try it

```
http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v5-webapp/
```

Behavior is identical to v3/v4 from the outside — same login flow, same 401s, same successful uploads/lists/downloads once logged in. Confirm with `curl`, same as before:

```bash
# No token — blocked:
curl -F "file=@/etc/hostname" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v5-webapp/s3/upload
# → 401, "Missing or incorrect token." (AuthFilter rejected before any Lambda call happens)

# Correct token — works, and this request DID trigger a Lambda invocation:
curl -H "Authorization: Bearer claude26" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v5-webapp/s3/list
# → 200, real JSON from the bucket
```

**The genuinely new demo this version makes possible:** change the token **without touching this project at all.**
1. Lambda console → `CheckTokenIsValid` → Configuration → Environment variables → change `TOKEN`'s value → Save. No rebuild, no redeploy of this `.war`.
2. Immediately retry the old token with `curl` → now `401`.
3. Retry with the new token → `200`.

✅ **Checkpoint:** this round-trip (change the env var, old token stops working, new token works) should take under a minute and involve zero Java, zero Maven, zero redeploy of `s3-v5-webapp`. That gap — between "this requires a code change" and "this requires a console click" — is the entire point of the lecture.

## Notes for the instructor

- **Verified by compile/package AND a live round-trip against a real deployed Lambda function** while preparing this guide: the `curl` sequence above (no token → 401, correct token → 200/real data, wrong token → 401) behaves exactly as described, and the environment-variable-change demo works as described.
- **This is the natural "after" to pair with `s3-v4-webapp`'s "before" in class** — diff `AuthConfig.java` side by side between the two. Nothing about `AuthFilter`, `LoginServlet`'s structure, or any of the four S3 servlets changed at the call-site level; only what's *inside* `isAuthorized` changed. That's the lesson: Lambda is a new way to run code, not a reason to redesign everything around it.
- **The `functionError()` check in `AuthConfig.isAuthorized(String)` is worth narrating live:** a Lambda invocation can return successfully at the SDK/network level while the function itself threw an exception inside its handler — those are two different failure modes, and the SDK surfaces the second one via `InvokeResponse.functionError()`, not an exception on the Java side. Treating any non-`true` outcome (including a function error) as "not authorized" means this auth check **fails closed** — a broken or misconfigured Lambda function locks everyone out, rather than accidentally letting everyone in. Ask the class: is fail-closed the right choice here? (Yes — for an authorization check specifically, availability should never be purchased at the cost of security.)
- **Cold starts are a legitimate, visible side effect worth mentioning, not hiding:** the first request after the Lambda function has been idle for a while will be noticeably slower than subsequent ones (the runtime has to initialize a fresh execution environment). Demonstrate it if time allows — invoke once after letting it sit idle, then immediately again, and compare. This is a real, known Lambda tradeoff, not a bug in this project.
- **Why this Lambda function is a poor fit for a real production system, worth being honest about:** a network call (EC2 → Lambda) now happens on **every single** S3-backed request, where a local `String.equals` used to be effectively free. For a toy example this is invisible; for a high-traffic application it is a real latency and cost concern. The lecture's point isn't "always do this" — it's "here is a new tool, here is exactly what it costs to use it this way."
