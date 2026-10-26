# S3 Serverless Lambdas

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| JDK | Java Development Kit |
| API | Application Programming Interface |
| S3 | Simple Storage Service (AWS's object storage service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |
| URL | Uniform Resource Locator |
| JSON | JavaScript Object Notation |
| CORS | Cross-Origin Resource Sharing |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |

## What this is

Five small, independent AWS Lambda functions that together replace every servlet in `labs/s3-v5-webapp/` — the last version of this app that ran on the EC2 instance at all. Each servlet becomes its own Lambda function, invoked directly by the browser via its own **Lambda Function URL** (no API Gateway, no load balancer, no server of any kind in front of it):

| Old servlet (`s3-v5-webapp`) | New Lambda function |
|---|---|
| `LoginServlet` | `LoginFunction` |
| `S3ListServlet` | `S3ListFunction` |
| `S3UploadServlet` | `S3UploadFunction` |
| `S3DownloadServlet` | `S3DownloadFunction` |
| `S3GetBucketName` | `S3GetBucketNameFunction` |

This is the working example for the "Going Serverless" session — see `lectures/going-serverless-session-instructor-guide.md`. The static page that calls these functions is `labs/s3-serverless-webapp/` (deployed to **AWS Amplify**, not this EC2 instance — by the end of this session, nothing in this app runs on EC2 at all).

## Why five separate projects, not one

Each function deploys as its own independent `.jar`, to its own independent Lambda function, with its own independent Function URL. There is no shared runtime process the way Tomcat was one process hosting every servlet — so there is no single Maven project that builds "the app" anymore. `mvn clean package` inside each function's own directory produces exactly the one `.jar` that function needs.

## What's duplicated, and why

`AuthCheck.java` and `Cors.java` are identical, byte-for-byte, across all five function directories. This isn't an oversight — it's the actual, visible cost of splitting one application into several separately deployed units. In `s3-v5-webapp` there was one `AuthFilter` and one shared `AuthConfig`, each written once. Here there are five copies of the same small class, because there is no shared classpath between five separately deployed `.jar` files. A real system at this scale would usually reach for a shared library published to an internal artifact repository, or accept the duplication for something this small — worth asking the class which they'd choose, and when the tradeoff flips.

## No `AuthFilter` anymore

`s3-v4-webapp` centralized the token check into one `Filter` specifically so it wouldn't be duplicated across four servlets. That centralization doesn't carry over here: there is no single request pipeline for multiple Lambda functions to share. Every function calls `AuthCheck.isAuthorized(...)` — which itself invokes the existing `CheckTokenIsValid` Lambda function from `labs/check-token-is-valid-lambda/` — as the very first thing its own handler does. This is a deliberate step back from the "Security of vs. in the Cloud" session's own teaching point, worth naming out loud in class rather than quietly dropping.

## Why CORS shows up for the first time in this session

Every earlier version of this app was one origin: the browser loaded `index.html` from the same Tomcat server, on the same host and port, that also served `/s3/list`, `/s3/upload`, etc. Browsers never need permission to call back to the page's own origin. Now the page is served from an Amplify domain and each function has a *different* domain — five different origins, none of them the page's own. Without `Access-Control-Allow-Origin` (and the other `Cors.HEADERS` entries) on every response, the browser would still make the network request, but it would throw the response away before your JavaScript ever sees it. See `Cors.java` in each function.

## Project layout

```
s3-serverless-lambdas/
├── LoginFunction/
│   ├── pom.xml
│   └── src/main/java/edu/cloudcomputing26/lambda/
│       ├── LoginFunction.java      # handler — replaces LoginServlet
│       ├── AuthCheck.java          # invokes CheckTokenIsValid
│       └── Cors.java               # shared CORS headers
├── S3ListFunction/          (same shape — replaces S3ListServlet)
├── S3UploadFunction/        (same shape — replaces S3UploadServlet; see below, NOT a lift-and-shift)
├── S3DownloadFunction/      (same shape — replaces S3DownloadServlet)
└── S3GetBucketNameFunction/ (same shape — replaces S3GetBucketName)
```

## Upload: the one function that genuinely had to change shape

`S3UploadServlet` accepted `multipart/form-data` — a `<form enctype="multipart/form-data">` submission, parsed for free by Tomcat's `request.getPart(...)`. A bare Lambda function has no servlet container behind it to do that parsing, and hand-writing a multipart parser would add real complexity that has nothing to do with Lambda itself.

Instead, `S3UploadFunction` expects the **raw file bytes as the request body**, with the filename in a custom `X-Filename` header — matching what the updated `index.html` now sends (`fetch(..., { body: file })` instead of `FormData`). This is a real architectural consequence of going serverless, not an arbitrary simplification: a Lambda Function URL event hands your code one body and one set of headers, nothing more structured than that.

A related, smaller wrinkle in both directions:
- **Upload:** a Function URL / API Gateway v2.0 event base64-encodes the body whenever it isn't valid UTF-8 text, and sets `isBase64Encoded` to `true` when it did. `S3UploadFunction` checks that flag before deciding whether to decode.
- **Download:** `S3DownloadFunction`'s response does the same thing in reverse — `withIsBase64Encoded(true)` plus a base64-encoded body — since the response has to travel as JSON too. Also worth flagging: a Function URL's synchronous response is capped at **6 MB**, a limit the old servlet (which streamed straight from S3 to the HTTP response) never had.

## Build each function

```bash
cd LoginFunction && mvn clean package && cd ..
cd S3ListFunction && mvn clean package && cd ..
cd S3UploadFunction && mvn clean package && cd ..
cd S3DownloadFunction && mvn clean package && cd ..
cd S3GetBucketNameFunction && mvn clean package && cd ..
```

✅ **Checkpoint:** each directory's `target/` now contains one shaded "fat jar" (e.g. `target/login-function.jar`) — that's the file to upload, not the `original-*.jar` the Shade plugin also leaves behind.

**Before building, edit each function's `BUCKET_NAME` constant** (every function except `LoginFunction`, which has no S3 work of its own) to match your own bucket — same `/** EDIT ME */` convention as `S3Config.BUCKET_NAME` in every earlier version of this app.

## Create and configure each function (AWS Console)

Repeat for each of the five functions (substitute each one's own name, handler class, and `.jar`):

1. **Lambda console → Create function → Author from scratch.**
2. **Function name:** e.g. `S3ListFunction` (match the class name for clarity).
3. **Runtime:** Java 21.
4. **Create function.**
5. **Code tab → Upload from → .zip or .jar file** → upload that function's `target/*.jar`.
6. **Runtime settings → Edit → Handler:** `edu.cloudcomputing26.lambda.<ClassName>::handleRequest` — e.g. `edu.cloudcomputing26.lambda.S3ListFunction::handleRequest`.
7. **Configuration tab → Function URL → Create function URL.** Auth type: **NONE** for this lab (the app does its own token check inside the handler — see the next section for exactly what that means and why it's not actually "no security"). Copy the resulting URL.
8. **Configuration tab → Permissions → execution role** → attach the same `lambda:InvokeFunction` policy from the "AWS Lambda" session (`resources/cto-lambda-invoke-policy.json`, scoped to `CheckTokenIsValid`'s ARN) — every one of these five functions now needs it, since every one calls `CheckTokenIsValid` itself. Each function that touches S3 (every one except `LoginFunction`) also needs the same S3 permissions the EC2 instance role used to have.

✅ **Checkpoint — test `S3GetBucketNameFunction` directly in the console first**, same as `CheckTokenIsValid` was tested in the last session: Test tab → a test event shaped like an API Gateway v2.0 request (the console has a built-in `apigw-requestauth` or similar template to start from) with an `Authorization: Bearer <your token>` header → should return your bucket name, status 200.

## Auth type: NONE is not the same as "no authentication"

**Concept check:** the Lambda console's "Auth type: NONE" setting controls whether *AWS IAM* gates who can invoke this Function URL at all. Setting it to NONE means anyone on the internet can send this function a request — but that is not the same question as "is the request authorized to do anything once it arrives." `AuthCheck.isAuthorized(...)`, running as the first line of every handler, is a completely separate, application-layer check — the same distinction the "Security of vs. in the Cloud" session drew between the EC2 instance's IAM role and `AuthFilter`. **IAM Auth type is about who may invoke; `AuthCheck` is about what they're allowed to do once they have.**

## Try it (once Function URLs are wired into `index.html` — see `labs/s3-serverless-webapp/`)

```bash
# No token — rejected inside the handler, before any S3 call happens:
curl -X POST <LOGIN_FUNCTION_URL> -d "wrong-token"
```
✅ **Checkpoint:** `401`, `Missing or incorrect token.`

```bash
curl <LIST_FUNCTION_URL> -H "Authorization: Bearer <your token>"
```
✅ **Checkpoint:** `200`, real JSON listing your bucket's contents — same result every earlier version of this app produced, from a URL on a completely different domain than the page that calls it.

## Notes for the instructor

- **Verified by compile/package only for all five functions**, same caveat as `check-token-is-valid-lambda`'s own README — live Function URL deployment and IAM wiring are console steps left for the student (and instructor) to do by hand; see the lecture's checkpoints for exact console steps and the policy JSON to attach.
- **A good moment to revisit "what are you paying for"** from the AWS Lambda session: five functions, each billed only while actually handling a request, replacing one EC2 instance that was billed by the hour whether or not anyone was using the app at all.
