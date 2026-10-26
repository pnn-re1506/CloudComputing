# CheckTokenIsValid Lambda

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| JDK | Java Development Kit |
| CLI | Command Line Interface |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| JSON | JavaScript Object Notation |
| JVM | Java Virtual Machine |

## What this is

A single AWS Lambda function, written in Java, that answers exactly one question: **"is this the correct secret token?"** — nothing more. It's the motivating example for the "AWS Lambda" session, and it replaces the token comparison that used to live inside `AuthConfig.isAuthorized(request)` in `labs/s3-v3-webapp/` and `labs/s3-v4-webapp/`.

The comparison logic itself hasn't changed — it's still one `String.equals` against one shared secret. What changes is *where that comparison runs*: instead of comparing against a hardcoded Java constant inside the same Tomcat process as the servlets, it now runs as its own separately deployed, separately invoked, separately scaled unit of compute — and the correct token is configured as a **Lambda environment variable**, not a line of source code.

See `lectures/aws-lambda-session-instructor-guide.md` and `labs/s3-v5-webapp/` (the webapp project that calls this function) for the full session.

## Project layout

```
check-token-is-valid-lambda/
├── pom.xml                                                        # Maven build config (produces a Shade "fat jar")
└── src/main/java/edu/cloudcomputing26/lambda/
    └── CheckTokenIsValid.java                                     # the entire function
```

## The handler

```java
public class CheckTokenIsValid implements RequestHandler<String, Boolean> {

    @Override
    public Boolean handleRequest(String submittedToken, Context context) {
        String correctToken = System.getenv("TOKEN");
        return correctToken != null && correctToken.equals(submittedToken);
    }
}
```

- **Input:** the submitted token, as a plain JSON string (e.g. `"claude26"`). Lambda's Java runtime handles deserializing the invocation payload into the handler's declared input type (`String` here) automatically.
- **Output:** `true` or `false`, as a plain JSON boolean. Same automatic serialization, in reverse.
- **`System.getenv("TOKEN")`** — reads the function's own `TOKEN` environment variable, set in the Lambda console (Configuration → Environment variables), not in this source file. This is the Lambda equivalent of `AuthConfig.TOKEN`'s `/** EDIT ME */` constant — except changing it is now a console setting, with **no redeploy of this code required at all**.

## Prerequisites

- **JDK 21** (or newer) and **Maven** — same as every other Java project in this course.
- No AWS SDK dependency here at all — this project is *called*, it doesn't call anything else. (Contrast with `s3-v5-webapp`, which needs the SDK's Lambda module to invoke this function.)

## Build it

```bash
mvn clean package
```

✅ **Checkpoint:** `target/check-token-is-valid-lambda.jar` exists after the build — this is the "fat jar" (via `maven-shade-plugin`), bundling `aws-lambda-java-core` alongside the one compiled class. This is the file you upload to Lambda, not `target/original-check-token-is-valid-lambda.jar` (the unshaded jar the plugin also leaves behind).

## Create and configure the function (AWS Console)

1. **Lambda console → Create function → Author from scratch.**
2. **Function name:** `CheckTokenIsValid`.
3. **Runtime:** Java 21.
4. **Architecture:** x86_64 (default is fine).
5. **Create function.**
6. **Code tab → Upload from → .zip or .jar file** → upload `target/check-token-is-valid-lambda.jar`.
7. **Runtime settings → Edit → Handler:** `edu.cloudcomputing26.lambda.CheckTokenIsValid::handleRequest` — this tells Lambda which class and method to call; it has no other way to find your code inside the jar.
8. **Configuration tab → Environment variables → Edit → Add environment variable:**
   - Key: `TOKEN`
   - Value: your secret token (match whatever `s3-v5-webapp`'s `AuthFilter` will send — e.g. `claude26`, same value used in v3/v4's `AuthConfig.TOKEN`).
9. **Save.**

✅ **Checkpoint — test it right here in the console, before wiring up the webapp at all:** Test tab → new test event → Event JSON:
```json
"claude26"
```
(a bare JSON string, matching the handler's `String` input type — **not** `{"token": "claude26"}`, which is a different shape and would deserialize to `null` with no error). → **Test** → Execution result should show `true`. Change the test event to `"wrong-token"` → **Test** again → `false`.

## Why this example, for this lecture

- **It's the smallest possible Lambda function that still does something real.** One `if`, nothing else — so the lecture's attention goes to Lambda's *mechanics* (environment variables, the invoke permission, the SDK call from `AuthFilter`), not to understanding new business logic.
- **It reuses a mechanism students already understand.** The token-comparison idea is exactly `AuthConfig.isAuthorized`, already covered in the "Security of vs. in the Cloud" session — the only genuinely new material is *where* that one comparison now runs.
- **The environment variable is the point, not an incidental detail.** `AuthConfig.TOKEN` was a hardcoded constant — changing it meant editing Java source, rebuilding, and redeploying a `.war`. `TOKEN` as a Lambda environment variable can be changed in the console in seconds, with zero code changes and zero redeploy — a concrete, inspectable example of configuration vs. code.

## Notes for the instructor

- **Verified by compile/package only, not a live AWS deployment of the Lambda function itself** (the invoke calls from `s3-v5-webapp` against a real deployed function were verified — see that project's README).
- **Handler string is a common point of confusion worth calling out live:** `edu.cloudcomputing26.lambda.CheckTokenIsValid::handleRequest` has to match the fully-qualified class name and method name exactly, or every invocation fails with `ClassNotFoundException`/`NoSuchMethodException` at invoke time (not at upload time — Lambda doesn't validate the handler string when you upload the jar, only when something tries to invoke it). This is a good live-demo moment: deliberately typo the handler string, invoke, show the error, fix it.
- **Why `System.getenv("TOKEN")` and not a parameter:** environment variables are the standard way to pass configuration into a Lambda function, precisely because they survive independently of the deployed code — the same mental model as `S3Config.BUCKET_NAME` being "configuration that happens to live in code" versus this being "configuration that lives outside code entirely." Worth asking students explicitly which one is better, and for what — the answer is genuinely "it depends," not "environment variables always win."
