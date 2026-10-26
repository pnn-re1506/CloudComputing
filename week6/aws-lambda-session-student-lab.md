# Lab: AWS Lambda

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

**You will need:** `s3-v4-webapp` already built, deployed, and working against your own bucket from the "Security of vs. in the Cloud" lab, plus your EC2 instance running.

By the end of this lab you will have written and deployed your first AWS Lambda function, called it from your webapp instead of checking the token locally, and rotated the secret token with zero code changes at all.

---

## Part 1 — What Lambda is, in one sentence

**AWS Lambda runs a specific piece of code, on demand, without you provisioning a server for it.** Contrast with the EC2 instance you've been using all course: you launched it, it's been running (or stopped and restarted) the whole time, whether or not a request is actually hitting it right now. A Lambda function only runs — and, under the standard pricing model, only gets billed — when something actually invokes it.

This is sometimes called "serverless" compute. Not because there's literally no server anywhere — there is, run by AWS — but because you never provision, see, or manage one yourself.

**Today's example:** the token check from `AuthConfig.isAuthorized` — "is this the correct secret token?" — moves out of your webapp's own code and into a Lambda function of its own, called `CheckTokenIsValid`.

---

## Part 2 — Build and test the Lambda function, on its own

**Read the whole function first** — `labs/check-token-is-valid-lambda/src/main/java/edu/cloudcomputing26/lambda/CheckTokenIsValid.java`:

```java
public class CheckTokenIsValid implements RequestHandler<String, Boolean> {

    @Override
    public Boolean handleRequest(String submittedToken, Context context) {
        String correctToken = System.getenv("TOKEN");
        return correctToken != null && correctToken.equals(submittedToken);
    }
}
```

- `RequestHandler<String, Boolean>` — Lambda's Java runtime deserializes the invocation's JSON payload into the input type (`String`), and serializes whatever you return back into JSON.
- `System.getenv("TOKEN")` — reads an **environment variable**, set outside this code (next part).

**Build it:**
```bash
cd labs/check-token-is-valid-lambda
mvn clean package
```
✅ **Checkpoint:** `target/check-token-is-valid-lambda.jar` exists. (Ignore `target/original-check-token-is-valid-lambda.jar` — that's the unshaded jar, without `aws-lambda-java-core` bundled in; it won't work when uploaded.)

**Create the function (AWS Console):**
1. Lambda console → **Create function** → **Author from scratch**.
2. Function name: `CheckTokenIsValid`. Runtime: **Java 21**.
3. **Create function.**
4. **Code** tab → **Upload from** → **.zip or .jar file** → upload `target/check-token-is-valid-lambda.jar`.
5. **Runtime settings** → **Edit** → **Handler:** `edu.cloudcomputing26.lambda.CheckTokenIsValid::handleRequest`

**Set the token as an environment variable:**
6. **Configuration** tab → **Environment variables** → **Edit** → **Add environment variable**:
   - Key: `TOKEN`
   - Value: pick your own secret (e.g. the same one you used for `AuthConfig.TOKEN` in `s3-v4-webapp`)
7. **Save.**

✅ **Checkpoint — test it directly in the console, no webapp involved yet:** **Test** tab → new test event → Event JSON:
```json
"claude26"
```
(replace with your own token) → **Test** → result should be `true`. Change the event to `"wrong-token"` → **Test** again → result should be `false`.

**Question to consider:** why does the test event have to be a bare JSON string in quotes (`"claude26"`), and not `{"token": "claude26"}`? (Because the handler's input type is `String` — Lambda deserializes the JSON payload directly into that type. An object shape like `{"token": "claude26"}` doesn't match `String` and would come through as `null`, not an error — a quiet, confusing failure if you don't know to expect it.)

---

## Part 3 — Update the EC2 instance's IAM role

Your webapp needs permission to invoke this function. Add this as a **new inline policy** on the **same IAM role already attached to your EC2 instance** (the one from the S3 session) — IAM console → Roles → your instance role → Add permissions → Create inline policy → JSON:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "InvokeCheckTokenIsValidOnly",
      "Effect": "Allow",
      "Action": "lambda:InvokeFunction",
      "Resource": "arn:aws:lambda:ap-southeast-1:<YOUR_ACCOUNT_ID>:function:CheckTokenIsValid"
    }
  ]
}
```

Replace `<YOUR_ACCOUNT_ID>` with your own 12-digit AWS account ID (visible in the console's top-right account menu, or via `aws sts get-caller-identity`).

**Notice what this grants, and what it deliberately doesn't:** only `lambda:InvokeFunction`, only on this one function's exact ARN — not `lambda:*`, not `Resource: "*"`. A broader grant would let anything running on your EC2 instance do things like rewrite this function's own `TOKEN` environment variable, or invoke completely unrelated functions in your account.

✅ **Checkpoint — verify from the EC2 instance itself, before touching the webapp:**
```bash
aws lambda invoke --function-name CheckTokenIsValid --payload '"claude26"' --cli-binary-format raw-in-base64-out /tmp/out.json && cat /tmp/out.json
```
Should print `true` (or `false` for a wrong token), with no `AccessDeniedException`. If you get `AccessDeniedException`, the IAM policy above isn't attached correctly yet — fix that before moving on.

---

## Part 4 — Build and deploy `s3-v5-webapp`

Open `s3-v5-webapp`'s `AuthConfig.java` and compare it to `s3-v4-webapp`'s. Notice what's **gone**: there's no `TOKEN` constant anywhere in this project anymore. What's **new**:

```java
static boolean isAuthorized(String submittedToken) {
    String jsonPayload = "\"" + submittedToken.replace("\"", "\\\"") + "\"";

    InvokeRequest invokeRequest = InvokeRequest.builder()
            .functionName(FUNCTION_NAME)
            .payload(SdkBytes.fromUtf8String(jsonPayload))
            .build();

    InvokeResponse invokeResponse = CLIENT.invoke(invokeRequest);

    if (invokeResponse.functionError() != null) {
        return false;
    }

    String responseJson = invokeResponse.payload().asString(StandardCharsets.UTF_8).trim();
    return "true".equals(responseJson);
}
```

Same SDK pattern as `S3Config`'s `S3Client` — one shared `LambdaClient`, built once, no access keys anywhere (same instance-role credential chain). `AuthFilter` and `LoginServlet` are otherwise unchanged from v4 — they still just call `AuthConfig.isAuthorized(...)`; they have no idea it now makes a network call.

**Before building, edit `AuthConfig.java` only if you named your function differently:**
```java
private static final String FUNCTION_NAME = "CheckTokenIsValid";
```
There's no token to edit here anymore — that lives solely in the Lambda function's `TOKEN` environment variable from Part 2.

Also edit `S3Config.java` as usual:
```java
static final String BUCKET_NAME = "cloud26-yourname-bucket";
```

Build and deploy exactly as before:
```bash
mvn clean package
scp -i ~/keys/cloud26-key.pem target/s3-v5-webapp.war ubuntu@<YOUR_INSTANCE_PUBLIC_IP>:/tmp/
ssh -i ~/keys/cloud26-key.pem ubuntu@<YOUR_INSTANCE_PUBLIC_IP>
sudo cp /tmp/s3-v5-webapp.war /opt/tomcat/webapps/
sudo chown tomcat: /opt/tomcat/webapps/s3-v5-webapp.war
```

**Try it — same as v3/v4, it should behave identically from the outside:**
```bash
# No token — rejected before any Lambda call happens:
curl -F "file=@/etc/hostname" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v5-webapp/s3/upload

# Correct token — this one DOES call Lambda:
curl -H "Authorization: Bearer <your-token>" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v5-webapp/s3/list
```
✅ **Checkpoint:** the first returns `401`, the second returns your real bucket listing as JSON — same results as `s3-v4-webapp` produced, even though the check now happens somewhere completely different.

---

## Part 5 — Rotate the token with zero redeploys

This is the whole point of today's lab. Do not skip it.

1. Lambda console → `CheckTokenIsValid` → **Configuration** → **Environment variables** → **Edit** → change `TOKEN`'s value to something new → **Save**.
2. Retry your **old** token with `curl` → now `401`.
3. Retry with the **new** token → `200`, real data.

**Question to consider:** what did you just rebuild or redeploy, in `s3-v5-webapp`, to make that token change take effect? (Nothing — no `mvn package`, no `scp`, no restart. One console field, one save.) Compare that to how you had to rotate the token back in `s3-v3-webapp`/`s3-v4-webapp` (edit `AuthConfig.TOKEN`, rebuild, redeploy). That difference — not the security mechanism itself, which hasn't changed — is what today's lecture was actually about.

---

## Part 6 — Think about the tradeoff

Lambda didn't make this faster — every `/s3/*` request to `s3-v5-webapp` now makes a real network call (EC2 → Lambda) to answer a question that used to be a single in-memory string comparison. For this small a check, that's a real cost, not a free upgrade.

**Question to consider:** can you think of a situation where moving a check like this into its own Lambda function is clearly worth that cost? What about a situation where it clearly isn't? There's no single right answer expected here — but you should be able to name at least one concrete factor on each side (e.g. "worth it if multiple different applications all need to share this exact check," or "not worth it if this one webapp is the only caller and every millisecond of latency matters").

**Before you leave:** stop your EC2 instance as usual.
