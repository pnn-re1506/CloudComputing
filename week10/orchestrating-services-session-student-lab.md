# Lab: Orchestrating Services

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |
| JSON | JavaScript Object Notation |
| ARN | Amazon Resource Name (AWS's unique identifier format for a resource) |
| CLI | Command Line Interface |
| URL | Uniform Resource Locator |
| ASL | Amazon States Language (the JSON-based language a Step Functions state machine is defined in) |
| SQS | Simple Queue Service |
| SNS | Simple Notification Service |

**You will need:** your working `S3UploadFunction`/`S3ListFunction` against the `FileNotes` table from "Database Layer with DynamoDB," already deployed. Today's starting point is a **fresh copy**, not an edit of last session's — `labs/s3-serverless-lambdas-orchestration/`. Your existing `Cloud26*` functions and Amplify app from before today are untouched; today's steps deploy new code on top of the same deployed function names.

**The gap this lab is about:** last session's `S3UploadFunction` does two things — uploads a file to S3, then writes a note about it to DynamoDB — as two separate, independent calls. If the first succeeds and the second fails, you get a file with no note, and nothing notices or fixes it. We accepted that gap last session. Today we build the fix, twice: once by hand, and once using a managed AWS service built for exactly this problem.

**Before you start:** today's Part 2 needs new IAM permissions beyond what earlier sessions granted — creating a Step Functions state machine, letting `S3UploadFunction` start/check it, and letting the state machine itself call DynamoDB and S3. If any step below returns `AccessDenied`, check with your instructor rather than trying to work around it.

---

## Part 1 — Build the fix by hand (exercise — no solution given)

This part is deliberately open-ended. You are not given a solution to read through — you are given a problem and a set of requirements, and you write the code. Different, reasonable solutions are expected; be ready to explain the choices yours makes.

**Starting code:** `labs/orchestrating-services-part1-lab-starting-code.zip` contains the `S3UploadFunction` module in its pre-exercise state — last session's plain S3 upload + DynamoDB `PutItem` version, no retry, no compensation — with a `<YOUR_BUCKET_NAME>` placeholder for you to fill in with your own values. Use this if you need a clean copy to start from.

### See the problem happen, before fixing anything

`S3UploadFunction.java` right now still does what last session's version did: upload to S3, then one `PutItem` call to DynamoDB, with a plain `try`/`catch` around each — no retry, no cleanup on failure. Watch this gap actually happen, with the exact same `curl` command you'll reuse later to check your fix:

```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: orphan-demo.txt" -H "X-Note: should%20get%20cleaned%20up" --data-binary "hello"
```

Right now this returns `200` and the note shows up — so first, **ask your instructor to temporarily break the DynamoDB write** (point the table-name constant at a table that doesn't exist, or temporarily remove `dynamodb:PutItem` from the execution role), and run the exact same command again:

```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: orphan-demo.txt" -H "X-Note: should%20get%20cleaned%20up" --data-binary "hello"
```

✅ **Checkpoint:** `500`, a plain DynamoDB error, no mention of cleanup. Now confirm the damage directly:

```bash
curl <LIST_URL> -H "Authorization: Bearer <your token>"
```

✅ **Checkpoint:** `orphan-demo.txt` **is** in the list — the file is genuinely sitting in S3 — but it has no note, because the second call never ran. Nothing anywhere flagged this or cleaned it up. **This is the exact gap you're about to fix.** Leave the DynamoDB permission/table broken for now — Part 1's build will fix this same scenario, and you'll re-run this identical `curl` sequence as your checkpoint once your code is in place. Don't delete `orphan-demo.txt` yet; your fixed code should be the thing that removes it.

### The problem

Open `labs/s3-serverless-lambdas-orchestration/S3UploadFunction/src/main/java/edu/cloudcomputing26/lambda/S3UploadFunction.java` — the same file, still showing the code that just produced the orphaned file above. Your job is to change what happens **after the S3 upload succeeds**, so that:

1. **If the DynamoDB write fails, it gets retried** some reasonable number of times before giving up. (How many attempts? How long to wait between them? Your call — but be ready to say why you picked what you picked.)
2. **If every retry attempt fails, the function compensates**: it deletes the S3 object it just uploaded, so the end state is "nothing happened" rather than "an orphaned file with no note."
3. **The response the caller gets back should say which of these happened** — succeeded on the first try, succeeded after retrying, failed and cleaned up, or failed and *couldn't even clean up*. That last case is not hypothetical — think about why it's possible before you get to the question below.

### Questions to work through before you write a line of code

**Think about this first, don't just start typing:** the compensating delete you're about to write is itself an AWS call — an `S3.deleteObject` — made from inside this same Lambda invocation. What happens if *that* call also fails? Your code needs an answer for this case specifically, not just for "the DynamoDB write failed." What should the function tell the caller in that situation, and how is it meaningfully different from a normal upload failure?

**Think about retry timing:** fixed delay between attempts, or a growing delay (backoff)? Either is a legitimate, gradeable answer for this exercise — but know which one you picked and be ready to defend it.

**Think about what "attempt" means for your response body:** if the write succeeds on attempt 2, does the caller need to know that, or is "it worked" enough? There's no single right answer — this is a design choice about how much of your internal retry logic should leak into the API response.

### What to build it in

Write your solution directly in `S3UploadFunction.java`'s existing handler — don't create a second Lambda function that calls the first. The point of this exercise is feeling what retry-and-compensate logic costs to write by hand, inside one function, not practicing how to split work across functions (a different, legitimate topic for a different day).

You will need the S3 SDK's `DeleteObjectRequest` (already imported via `S3Client` in the file) and the DynamoDB SDK's existing `PutItemRequest`/`DynamoDbException` types from last session's version — no new Maven dependency should be needed beyond what `-db`'s copy already had.

**Build and redeploy once you have something working** — keep the DynamoDB write **broken** (same table name/permission you broke above), since the first thing you're checking is the failure path, not the happy path:
```bash
cd labs/s3-serverless-lambdas-orchestration/S3UploadFunction && mvn clean package
```
Upload the jar to `Cloud26S3UploadFunction` via the console — no new IAM permissions needed for this part; the execution role already has everything a correct solution calls (`s3:PutObject`, `s3:DeleteObject`, `dynamodb:PutItem`). If `s3:DeleteObject` turns out to be missing, ask your instructor rather than trying to grant it yourself.

✅ **Checkpoint — re-run the exact same `curl` command from the demo above, DynamoDB still broken:**
```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: orphan-demo.txt" -H "X-Note: should%20get%20cleaned%20up" --data-binary "hello"
```
`500`, but now the body should say the upload failed **and the file was removed** — not just a bare DynamoDB error like before. Confirm it directly, with the exact same `curl` you used to prove the damage earlier:
```bash
curl <LIST_URL> -H "Authorization: Bearer <your token>"
```
`orphan-demo.txt` should **not** appear anymore — your compensating delete actually ran. This is the same check, same file, same command as the "before" demo — now showing the gap closed.

**Now restore the DynamoDB table name/permission, and confirm the happy path still works:**
```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: retry-test.txt" -H "X-Note: works%20fine" --data-binary "hello"
```
✅ **Checkpoint:** `200`, note shows up correctly — your retry/compensation code didn't break the ordinary case.

### In-class discussion

Be ready to compare your solution with classmates': how many retry attempts did you pick, and why? Fixed delay or backoff? What exactly does your function tell the caller when the compensating delete itself fails — and did you even handle that case on your first attempt? There is real room for different, equally defensible answers here — the discussion is the point, not converging on one "correct" version.

**One question to sit with regardless of how your code turned out:** if this Lambda invocation itself crashes — times out, gets killed, the environment gets recycled — partway through your retry loop, what happens? (Think about where your retry state actually lives.) Keep your answer in mind; it's exactly what Part 2 is about.

---

## Part 2 — The same fix, as a Step Functions state machine

Part 1's code — however your version turned out — has a limitation no amount of extra retries fixes: all of its state (which attempt it's on, whether compensation already ran) lives in that one Lambda invocation's memory. If the invocation itself dies mid-retry, nothing anywhere knows a retry was even in progress. Part 2 builds the same fix using a service designed specifically to survive that.

**Starting code:** `labs/orchestrating-services-part2-lab-starting-code.zip` contains everything Part 2 needs — the `S3UploadFunction` module (reset to last session's plain S3 + DynamoDB version, so Part 2 is a clean build regardless of how Part 1 went), `FileNotesOrchestration.asl.json`, and the two new IAM policy files, all with placeholders (`<YOUR_BUCKET_NAME>`, `<YOUR_TABLE_NAME>`, `<ACCOUNT_ID>`) for you to fill in with your own values. It does **not** include your Part 1 solution (`S3UploadFunctionHandRolled.java`) — keep that file from Part 1 as you had it; this zip only replaces/adds what Part 2 needs.

### What Step Functions is, briefly

A managed state machine service: you describe a sequence of steps in JSON (Amazon States Language, ASL), and AWS runs and remembers the state of an in-progress **execution** — durably, outside any single Lambda. There are two workflow types: **Standard** keeps a full, durable execution history you can inspect state by state in the console; **Express** is cheaper and lower-latency but only logs to CloudWatch if you opt in, with no visual replay. **This lab uses Standard** — the point here is seeing exactly what happened, not raw speed.

**A real constraint that shapes the design:** a Step Functions execution's input/output is capped at 256 KB, and isn't a place to carry arbitrary binary file bytes. So the state machine does **not** do the S3 upload — `S3UploadFunction` still does that itself, exactly as before. The state machine's job starts only after the file already exists in S3: the DynamoDB write, its retries, and the compensating delete on failure.

### Step 1 — Read the state machine definition

Open `labs/s3-serverless-lambdas-orchestration/FileNotesOrchestration.asl.json`:

```
                  ┌────────────────────────┐
                  │   SaveNoteToDynamoDB    │   Task: dynamodb:putItem
                  │                         │   Retry: up to 3 attempts,
                  └────────────┬────────────┘   backoff rate 2.0
                               │
          ┌────────────────────┴────────────────────┐
          │ on success                               │ on failure
          │                                           │ (after 3 failed retries)
          ▼                                           ▼
 ┌──────────────────┐                      ┌───────────────────────┐
 │   SaveSucceeded   │                      │  DeleteOrphanedUpload  │
 │     (Succeed)     │                      │                        │   Task: s3:deleteObject
 └──────────────────┘                      └───────────┬────────────┘
                                                         │
                                   ┌─────────────────────┴─────────────────────┐
                                   │ on success                                 │ on failure
                                   ▼                                             ▼
                        ┌───────────────────┐                     ┌─────────────────────────────┐
                        │     SaveFailed      │                     │  SaveFailedCompensation-      │
                        │       (Fail)        │                     │    AlsoFailed (Fail)          │
                        └───────────────────┘                     └─────────────────────────────┘
```

```json
{
  "StartAt": "SaveNoteToDynamoDB",
  "States": {
    "SaveNoteToDynamoDB": {
      "Type": "Task",
      "Resource": "arn:aws:states:::dynamodb:putItem",
      "Parameters": {
        "TableName": "<YOUR_TABLE_NAME>",
        "Item": {
          "fileKey": { "S.$": "$.fileKey" },
          "note": { "S.$": "$.note" }
        }
      },
      "Retry": [{ "ErrorEquals": ["States.ALL"], "IntervalSeconds": 1, "MaxAttempts": 3, "BackoffRate": 2.0 }],
      "Catch": [{ "ErrorEquals": ["States.ALL"], "ResultPath": "$.error", "Next": "DeleteOrphanedUpload" }],
      "Next": "SaveSucceeded"
    },
    "SaveSucceeded": { "Type": "Succeed" },
    "DeleteOrphanedUpload": {
      "Type": "Task",
      "Resource": "arn:aws:states:::aws-sdk:s3:deleteObject",
      "Parameters": { "Bucket.$": "$.bucketName", "Key.$": "$.fileKey" },
      "Catch": [{ "ErrorEquals": ["States.ALL"], "ResultPath": "$.compensationError", "Next": "SaveFailedCompensationAlsoFailed" }],
      "Next": "SaveFailed"
    },
    "SaveFailed": { "Type": "Fail", "Error": "SaveNoteFailed" },
    "SaveFailedCompensationAlsoFailed": { "Type": "Fail", "Error": "SaveNoteFailedAndCompensationFailed" }
  }
}
```

**Questions to consider — compare each of these directly against your own Part 1 code:**
- `Resource: arn:aws:states:::dynamodb:putItem` — Step Functions calls DynamoDB *directly*, with no Lambda in between. Your Part 1 code needed a running Lambda invocation just to make this one `PutItem` call; this state machine doesn't.
- `"S.$": "$.fileKey"` — the `.$` suffix means "read this value from the execution's JSON input." This is JSONPath — the same addressing idea as `jq` — doing the job your Java code's method calls did.
- `Retry` directly replaces whatever loop you wrote in Part 1. `BackoffRate: 2.0` gives *exponential* backoff — did your own version have that, or a fixed delay?
  - **The formula:** each retry's wait time is `IntervalSeconds × BackoffRate^(Attempt − 1)` — `IntervalSeconds` is the delay before the *first* retry, `BackoffRate` is the multiplier (defaults to `2.0` if you omit it), `Attempt` is the current retry number. With this state machine's own values (`IntervalSeconds: 1`, `BackoffRate: 2.0`), work out the wait before each of the 3 retries yourself before Step 6 — you'll check your answer against the real execution's timestamps there.
  - **Why exponential, not fixed?** A fixed delay retries every failing caller at the same rate, which can pile *more* simultaneous requests onto a downstream service that's already struggling — this is called the "thundering herd" problem. A growing delay spreads retries out instead of bunching them together.
- `Catch → DeleteOrphanedUpload` directly replaces whatever `catch` block called `S3.deleteObject` in your Part 1 code.
- `DeleteOrphanedUpload` has its own `Catch → SaveFailedCompensationAlsoFailed`. **Did your Part 1 code have the equivalent — a handled case for when the compensating delete itself fails?** This is not automatic here either — a state machine with no `Catch` on this step would simply die with an unhandled error, the same risk as forgetting that inner `catch` in Java.

### Step 2 — Create the state machine (console)

1. **Step Functions console → Create state machine → Write your workflow in code.**
2. Paste the ASL above, with `<YOUR_TABLE_NAME>` filled in (`FileNotes`).
3. **Type: Standard.**
4. **Name:** `FileNotesOrchestration` (must match the ARN the code in Step 3 calls).
5. **Execution role:** create a new role — attach `resources/stepfunctions-filenotes-orchestration-execution-policy.json` (fill in `<ACCOUNT_ID>` and `<YOUR_BUCKET_NAME>` with your own values first), scoped to exactly `dynamodb:PutItem` on `FileNotes` and `s3:DeleteObject` on your bucket. This is a **third, separate execution role** in this app now, distinct from any Lambda function's own role — same least-privilege-per-identity pattern as every other policy this course has attached.
6. **Create state machine.**

✅ **Checkpoint:** copy the state machine's ARN from its own console page — you'll need it in Step 3.

### Step 3 — Wire `S3UploadFunction` to call it

Edit `labs/s3-serverless-lambdas-orchestration/S3UploadFunction/src/main/java/edu/cloudcomputing26/lambda/S3UploadFunction.java` (this is a **different file** than the one you edited in Part 1 — `S3UploadFunction.java`, not `S3UploadFunctionHandRolled.java`; keep your Part 1 work intact in its own class rather than overwriting it, since you'll want both for the in-class comparison):

1. **Fill in the state machine's ARN** in the `STATE_MACHINE_ARN` constant near the top of the file.
2. **Add the new Maven dependency**, if it isn't already there: `software.amazon.awssdk:sfn` in `pom.xml` (same BOM every other AWS SDK module this course uses already pulls from).
3. **After the S3 upload succeeds**, instead of calling DynamoDB directly, start an execution:
```java
String executionInput = "{"
        + "\"fileKey\":\"" + escapeJson(key) + "\","
        + "\"note\":\"" + escapeJson(note) + "\","
        + "\"bucketName\":\"" + escapeJson(BUCKET_NAME) + "\""
        + "}";

StartExecutionResponse startResponse = SFN.startExecution(StartExecutionRequest.builder()
        .stateMachineArn(STATE_MACHINE_ARN)
        .input(executionInput)
        .build());

String executionArn = startResponse.executionArn();
```
4. **Standard workflows have no synchronous "start and wait" call** (`StartSyncExecution` exists, but only for Express) — so poll `DescribeExecution` in a loop until the execution leaves `RUNNING`, capped at a maximum number of attempts so a stuck execution can't hang this Lambda until its own timeout:
```java
for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
    DescribeExecutionResponse describeResponse = SFN.describeExecution(DescribeExecutionRequest.builder()
            .executionArn(executionArn)
            .build());

    if (describeResponse.status() == ExecutionStatus.RUNNING) {
        Thread.sleep(POLL_INTERVAL_MILLIS);
        continue;
    }

    // SUCCEEDED → 200. Anything else (FAILED/TIMED_OUT/ABORTED) → 500;
    // the state machine's own Catch has already run the compensating
    // delete by the time you see this.
}
```

**Question to consider:** this polling loop makes a Standard workflow *behave* synchronously from the browser's point of view, even though it's built on an asynchronous API underneath. This pattern has a name — **sync-over-async**. Where else have you seen this shape before (a long-running job API that hands you back an ID you poll)?

### Step 4 — Required IAM, and what to remove

Attach `resources/cto-lambda-stepfunctions-execution-policy.json` (fill in `<ACCOUNT_ID>`) to `S3UploadFunction`'s execution role — `states:StartExecution` and `states:DescribeExecution`, scoped to `FileNotesOrchestration`. This **replaces** the DynamoDB execution policy on this one function's role — `S3UploadFunction` no longer calls DynamoDB directly, so it no longer needs that permission. (`S3ListFunction`'s role is untouched.)

### Step 5 — Build, redeploy, and test

```bash
cd labs/s3-serverless-lambdas-orchestration/S3UploadFunction && mvn clean package
```
Upload the jar to `Cloud26S3UploadFunction`, and **make sure the function's handler setting points at `edu.cloudcomputing26.lambda.S3UploadFunction::handleRequest`** — not `S3UploadFunctionHandRolled`, which is what Part 1 pointed it at. Both classes are in this same jar; only the handler setting decides which one actually runs.

✅ **Checkpoint — the same `curl` command you'll reuse through Step 6:**
```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: sfn-demo.txt" -H "X-Note: works%20fine" --data-binary "hello"
```
`200`, file appears with its note. From the outside, nothing about calling this function looks different from Part 1's version — the entire change is what's happening behind that one HTTP response.

### Step 6 — Watch it fail, on purpose

Go to **Step Functions console → `FileNotesOrchestration` → Executions** and open the execution the upload above just triggered.

1. **Graph view** — `SaveNoteToDynamoDB` lit up green, straight to `SaveSucceeded`. Click the state itself and look at its Input/Output tabs — this is the same JSON you built in Step 3, now visible.

   **What your `curl` was actually waiting on:** the console shows the state machine's own timeline, but your `curl` only got one response, after a short pause. That pause is the `DescribeExecution` polling loop you wrote in Step 3 — `S3UploadFunction` calling `DescribeExecution` roughly every `POLL_INTERVAL_MILLIS`, asking "has this execution left `RUNNING` yet?", over and over, until it finally gets an answer. The Lambda has no special window into this console; it's making the same repeated "is it done?" call a human clicking refresh would, just automated. Each one of those polls is a real API call with its own latency, on top of whatever the state machine itself took — a cost your Part 1 code's single blocking `DYNAMO_DB.putItem()` call never had, since that call just returns the real answer directly, with nothing to poll for.
2. **Event history view** — a timestamped list of every state transition, recorded automatically, with no logging code anywhere in the ASL definition.

**Now force the failure, the same way you did in Part 1:** point the ASL's `TableName` at a table that doesn't exist (or ask your instructor to temporarily remove `dynamodb:PutItem` from the **state machine's own** execution role — not the Lambda's), redeploy that one field, then run the **exact same `curl` command again**, unchanged:
```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: sfn-demo.txt" -H "X-Note: works%20fine" --data-binary "hello"
```

✅ **Checkpoint:** `500` this time. Open this new execution in the console and narrate it state by state:
- `SaveNoteToDynamoDB` shows red, but not immediately — look at the timestamps between retry attempts. Do they match the 1s/2s/4s you worked out earlier from the formula? This is `BackoffRate: 2.0`, now real, not just a number in a JSON file.
- After `MaxAttempts` is exhausted, find the transition to `DeleteOrphanedUpload` in the graph — this is the `Catch` field, now drawn as a line instead of a key in an object.

**Confirm the compensation actually ran, with the same `<LIST_URL>` check from Part 1:**
```bash
curl <LIST_URL> -H "Authorization: Bearer <your token>"
```
`sfn-demo.txt` should **not** appear — even though the identical `curl` succeeded and listed it a moment ago, before you broke the table.

**Now restore the table name/permission, and confirm the happy path once more, with the same command a third time:**
```bash
curl -i -X POST <UPLOAD_URL> -H "Authorization: Bearer <your token>" -H "X-Filename: sfn-demo.txt" -H "X-Note: works%20fine" --data-binary "hello"
```
✅ **Checkpoint:** `200` again — same file name, same note, same command, three different outcomes across this one Step depending only on whether DynamoDB was reachable.

---

## Wrapping up: comparing what you built

You now have two real, independently deployable solutions to the same problem — your own Part 1 code (`S3UploadFunctionHandRolled.java`) and the Step Functions version (`S3UploadFunction.java`). Be ready to discuss, using your own code as the evidence rather than a general impression:

- **Durability:** if your Part 1 Lambda got killed mid-retry, what trace would be left anywhere? Compare to what Step Functions' execution history gives you for free.
- **Legibility:** could a classmate read your Part 1 code and immediately answer "what happens if the DynamoDB write fails three times," as quickly as they could answer it from the ASL's `Retry`/`Catch` fields?
- **Cost:** what did Step Functions cost you that your Part 1 version didn't need — a new service, a new execution role, the polling loop in Step 3? What did your Part 1 version cost that Step Functions didn't — everything you had to get right yourself?

**The honest limit, either way:** neither version is a true atomic transaction across S3 and DynamoDB — a real transaction guarantees all-or-nothing, enforced by the database itself. Both versions instead detect a failure *after* the S3 write already happened, then run a second, separate call to undo it — a call that can itself fail, which is exactly why both versions needed their own nested handling for that case. This pattern — local steps, each with a compensating action if a later step fails — is called the **saga pattern**. Step Functions didn't make the pattern unnecessary; it just gave you a more durable, more visible way to run one.

**One more real idea, not built today:** everything in this lab is **orchestration** — one piece of code (your Lambda, then the state machine) that explicitly knows the whole sequence and decides what happens on failure. The alternative architectural style is **choreography**: no single component knows the whole sequence — each service reacts to events on its own (e.g. `S3UploadFunction` publishes "file uploaded" to **EventBridge**; a separate Lambda, subscribed via an EventBridge rule, picks that up and writes the DynamoDB row, with **SNS** or **SQS** often involved for fan-out or durable per-consumer queues). Choreography decouples services from each other's failures and from waiting on each other — at the cost of exactly what orchestration gives you: one place that knows whether the whole operation actually succeeded. Today built and tested orchestration, twice; choreography is real, substantial material for a future session, not something covered hands-on today.
