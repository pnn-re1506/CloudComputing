# Lab: Going Serverless

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |
| JSON | JavaScript Object Notation |
| JDK | Java Development Kit |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| CLI | Command Line Interface |
| URL | Uniform Resource Locator |
| CORS | Cross-Origin Resource Sharing |
| UI | User Interface |

**You will need:** `s3-v5-webapp` already built, deployed, and working against your own bucket from the "AWS Lambda" lab, plus `CheckTokenIsValid` already created and working.

By the end of this lab you will have replaced every remaining servlet in your webapp with its own Lambda function, redeployed the static page to AWS Amplify, and shut down your EC2 instance for good.

---

## Part 1 — What's left to move

Last session moved one thing — the token check — into Lambda. Everything else was still a servlet, running inside Tomcat, on your EC2 instance. Today every remaining servlet becomes its own Lambda function, invoked directly from the browser via its own **Lambda Function URL** (no API Gateway — each function just gets its own HTTPS URL):

| Old servlet | New Lambda function |
|---|---|
| `LoginServlet` | `LoginFunction` |
| `S3ListServlet` | `S3ListFunction` |
| `S3UploadServlet` | `S3UploadFunction` |
| `S3DownloadServlet` | `S3DownloadFunction` |
| `S3GetBucketName` | `S3GetBucketNameFunction` |

And the static page (`index.html`) moves off Tomcat entirely, onto **AWS Amplify**.

---

## Part 2 — Build and deploy `LoginFunction`, `S3ListFunction`, `S3GetBucketNameFunction`

These three are close to a direct translation of their servlets — no new request format to deal with (that's Part 3). **Read `labs/s3-serverless-lambdas/S3ListFunction/src/main/java/edu/cloudcomputing26/lambda/S3ListFunction.java` first:**

```java
public class S3ListFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (!AuthCheck.isAuthorized(event.getHeaders())) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing or incorrect token.")
                    .build();
        }
        // ... same ListObjectsV2 call as S3ListServlet ...
    }
}
```

- **`APIGatewayV2HTTPEvent` / `APIGatewayV2HTTPResponse`** — the request/response shape a Lambda **Function URL** uses (same shape API Gateway HTTP APIs use — "payload format v2.0"). `event.getHeaders()` is how this function reads the `Authorization` header — there's no `HttpServletRequest` anymore.
- **`AuthCheck.isAuthorized(...)`** — does exactly what `AuthConfig.isAuthorized` did, by invoking `CheckTokenIsValid` the same way. There is no `AuthFilter` running in front of this function (see Part 4's question about why), so this check happens as the first line of the handler itself.

**Edit `BUCKET_NAME` in `S3ListFunction.java` and `S3GetBucketNameFunction.java`** (not `LoginFunction` — it never touches S3), then build each:
```bash
cd labs/s3-serverless-lambdas/LoginFunction && mvn clean package && cd ../..
cd labs/s3-serverless-lambdas/S3ListFunction && mvn clean package && cd ../..
cd labs/s3-serverless-lambdas/S3GetBucketNameFunction && mvn clean package && cd ../..
```
✅ **Checkpoint:** each project's `target/` has one shaded jar (e.g. `target/login-function.jar`) — not the `original-*.jar` also left behind.

**Create each function in the Lambda console** (repeat for all three, substituting the function's own name/handler/jar):
1. **Create function → Author from scratch.** Name: e.g. `S3ListFunction`. Runtime: **Java 21**.
2. **Code tab → Upload** the jar.
3. **Runtime settings → Edit → Handler:** `edu.cloudcomputing26.lambda.S3ListFunction::handleRequest`.
4. **Configuration → Function URL → Create function URL.** Auth type: **NONE**.
5. **Configuration → Permissions →** open the execution role → **Add permissions → Create inline policy → JSON** → paste `resources/cto-lambda-functions-execution-policy.json` (fill in `<YOUR_BUCKET_NAME>` and your account ID). **For `LoginFunction` only**, use `resources/cto-lambda-invoke-policy.json` instead — it never needs S3 access.

✅ **Checkpoint — test `S3ListFunction` directly in the console:** Test tab → pick an API Gateway v2.0 test event template → add under `headers`:
```json
"authorization": "Bearer claude26"
```
→ **Test** → should return your bucket's JSON listing, status 200.

**Question to consider:** `LoginFunction`'s execution role doesn't get the S3 permissions the other functions get. What would attaching them anyway violate? (Least privilege — a grant with no corresponding need. `LoginFunction` never calls S3 at all; its only job is checking a token.)

---

## Part 3 — Build and deploy `S3UploadFunction` and `S3DownloadFunction`

These two need real changes, not just a translation — there's no servlet container here to do multipart parsing or stream raw bytes back for you.

**Upload — read `S3UploadFunction.java`:**
```java
Map<String, String> headers = event.getHeaders();
String key = headers.get("x-filename");

byte[] fileBytes = Boolean.TRUE.equals(event.getIsBase64Encoded())
        ? Base64.getDecoder().decode(event.getBody())
        : event.getBody().getBytes(StandardCharsets.UTF_8);

S3.putObject(putRequest, RequestBody.fromBytes(fileBytes));
```
No `multipart/form-data`, no `@MultipartConfig`, no `request.getPart(...)` — the file's raw bytes travel as the whole request body, with the filename in a custom `X-Filename` header instead. `isBase64Encoded()` matters because a Function URL event base64-encodes the body whenever it isn't valid UTF-8 text (any binary file will not be).

**Download — read `S3DownloadFunction.java`:** does the reverse for the response —
```java
return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(...)
        .withIsBase64Encoded(true)
        .withBody(Base64.getEncoder().encodeToString(objectBytes.asByteArray()))
        .build();
```

**Question to consider:** why can't the old servlet's approach — just write bytes straight to `response.getOutputStream()` — work here at all? (A Lambda Function URL's response has to travel as one JSON object, and raw binary bytes can't go directly inside JSON — hence base64-encoding the body and setting the flag that tells the caller it's encoded.)

**Edit `BUCKET_NAME` in both files, build, and create both functions in the console**, same steps as Part 2 (both need `resources/cto-lambda-functions-execution-policy.json` — they touch S3). Don't forget the Function URL step for each.

✅ **Checkpoint — test `S3UploadFunction` end to end with `curl` once its Function URL exists:**
```bash
curl -X POST <S3UPLOAD_FUNCTION_URL> \
  -H "Authorization: Bearer claude26" \
  -H "X-Filename: test-upload.txt" \
  --data-binary @/etc/hostname
```
Should return `Uploaded successfully.` with your bucket name and the key `test-upload.txt`.

---

## Part 4 — Why there's no `AuthFilter` anymore

Open `AuthCheck.java` in two different function directories, side by side.

**Question to consider:** these two files are identical. In `s3-v4-webapp`, the whole point of `AuthFilter` was to write this check exactly once instead of once per servlet. Why doesn't that same centralization carry over here? (There's no single request pipeline anymore for multiple functions to share — each function is a separately deployed `.jar` with no shared classpath between them. The duplication here is the real, visible cost of splitting one application into five independently deployed pieces — not a mistake, but worth noticing as a genuine downside, not just "more files.")

---

## Part 5 — Deploy the static page to Amplify

Open `labs/s3-serverless-webapp/index.html`. Compare it to `s3-v5-webapp/index.html` — same login flow, same in-memory-only `token` variable (still never `localStorage`), same list/upload UI. What's different is only *where things point*:

```js
const LOGIN_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const BUCKET_NAME_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const LIST_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const UPLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const DOWNLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
```

**Paste in each Function URL** from the five functions you just created (Configuration → Function URL tab, in each function's console page).

**Notice `Cors.java` in every function project:**
```java
static final Map<String, String> HEADERS = Map.of(
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Headers", "authorization,content-type,x-filename",
        "Access-Control-Allow-Methods", "GET,POST,OPTIONS"
);
```
Every earlier version of this app never needed this — the page and every endpoint shared one Tomcat origin. Now the page lives on an Amplify domain and each function has its own, different domain — without these headers, the browser would silently discard every response before your page's own JavaScript ever saw it.

**Deploy to Amplify (console):**
1. **Amplify console → New app → Deploy without Git provider.**
2. App name: your choice.
3. Zip just `index.html` and drag-and-drop it in.
4. **Save and deploy.**

✅ **Checkpoint:** Amplify gives you a `https://<branch>.<app-id>.amplifyapp.com` URL. Visit it — the same login screen you've seen since `s3-v3-webapp`, now served from a domain that has never heard of Tomcat.

**Try the full flow:** log in, confirm the bucket name and file list load, upload a file, confirm it shows up in the list.

**Question to consider:** open your browser's network tab while doing this. How many different domains does one page load now talk to? (Six: the Amplify domain for the page itself, plus five separate Function URL domains — one per Lambda function. Every earlier version of this app talked to exactly one domain, for everything.)

**If something fails silently** (blank list, nothing happens, no error popup): check the browser console before anything else. A CORS failure shows up there, not as an alert from this page — the browser blocks the response before this page's own error handling ever runs.

### Why `curl` passed but the browser might still fail

If every `curl` checkpoint in Parts 2–3 worked, but the browser shows a CORS error anyway — even though `Cors.HEADERS` clearly includes `Access-Control-Allow-Origin` — there's a step `curl` never takes at all.

Any `fetch()` call that sends a header the browser treats as "non-simple" — `Authorization` and `X-Filename` both count — makes the browser send an automatic **preflight** first: a separate `OPTIONS` request, sent *before* your real `GET`/`POST`, asking "if I actually send this request with these headers, will you allow it?" The browser only sends your real request afterward, and only if the preflight comes back looking right. `curl` has no concept of this — it only ever sends the one request you told it to, which is exactly why this bug is invisible to every `curl` test you've already run.

**The actual bug:** every handler, as written in Parts 2–3, calls `AuthCheck.isAuthorized(event.getHeaders())` immediately, with no check for what kind of request this is. A preflight never carries your own custom headers — including `Authorization` — since the whole point is asking permission *before* exposing them. So every preflight lands in the `401` branch. But a `401` isn't a valid preflight response (the browser wants a plain `200`/`204` with the CORS headers, nothing else) — so the browser treats the whole thing as a CORS failure and **never sends your real request at all**. This is why the error shows up as a generic CORS error with no application response visible anywhere: there isn't one, because your function's actual logic never ran.

**The fix — add this to `Cors.java`:**
```java
static boolean isPreflight(APIGatewayV2HTTPEvent event) {
    return "OPTIONS".equalsIgnoreCase(event.getRequestContext().getHttp().getMethod());
}

static APIGatewayV2HTTPResponse preflightResponse() {
    return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(200)
            .withHeaders(HEADERS)
            .build();
}
```

**And add this as the very first lines of every handler's `handleRequest`, before `AuthCheck`:**
```java
if (Cors.isPreflight(event)) {
    return Cors.preflightResponse();
}
```

Apply this to all five functions, rebuild each one, and re-upload the new jars in the console — no Function URL or IAM changes needed, just new code. Retry the Amplify page afterward.

**Question to consider:** why does a preflight never carry the `Authorization` header, even though the real request sent right after it does? (Because the preflight's whole purpose is to ask permission *before* exposing anything sensitive — if the browser sent the real headers on the preflight too, asking first would accomplish nothing.) **Follow-up:** could you fix this instead by having `AuthCheck` treat a missing `Authorization` header as automatically authorized? (No — that would also let through every *real* unauthenticated request, not just preflights. Checking the request method first is what makes this safe: it identifies what *kind* of request this is, rather than loosening what counts as a valid token.)

---

## Part 6 — Shut down your EC2 instance for good

```bash
aws ec2 stop-instances --instance-ids <your-instance-id> --profile CTO
```

This isn't "stop it like every previous session, start it again next time." Nothing in this app needs this instance anymore. It can stay stopped permanently.

**Question to consider:** think back across every session this course. What did you actually gain today, and what did it cost you to get there? Try to name at least one concrete thing on each side — not just "serverless is more modern." (Gain: nothing in this app is billed while idle, no server to patch or SSH into, no "did I remember to stop the instance" habit. Cost: CORS configuration everywhere, a hand-rolled upload format instead of a plain HTML form, `AuthCheck` duplicated five times instead of centralized once, five separate things to deploy instead of one `.war`, and a harder debugging story — which of six domains failed, this time?)

**Closing question to consider:** for an app this small — one small team, light traffic — was today's change actually worth it? There's no single right answer expected, but a fair, honest one might be "not obviously" — today's architecture is objectively more complicated to reason about than one Tomcat process was. Serverless tends to pay off more clearly at a different scale (traffic that spikes unpredictably, many teams each owning separate pieces, needing to scale one endpoint without scaling everything else) — not automatically, just because it's newer.
