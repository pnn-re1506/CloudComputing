# Lab: More About Lambdas

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
| ms | milliseconds |

**You will need:** the five Lambda functions from "Going Serverless" — Java classes `LoginFunction`, `S3ListFunction`, `S3UploadFunction`, `S3DownloadFunction`, `S3GetBucketNameFunction`, deployed under this course's `Cloud26`-prefixed AWS Lambda function names (`Cloud26LoginLambda`, `Cloud26S3ListLambda`, `Cloud26S3UploadFunction`, `Cloud26S3DownloadFunction`, `Cloud26S3GetBucketNameFunction`) — already created, deployed, and verified working via `curl`, plus your EC2 instance still running. **Console and CLI steps below use the deployed Lambda function name, not the Java class name, wherever the two differ.**

**Before you start:** today's parts need IAM permissions beyond what earlier sessions granted — Amplify deployment (Part 1), creating/configuring a new S3 website bucket (Part 2), Lambda versions/aliases (Parts 5–6), and CloudWatch Logs/metrics/alarms (Parts 4, 7, 8). If any step below returns `AccessDenied`, this is why — check with your instructor rather than trying to work around it.

**Note:** "Going Serverless" was covered through converting the servlets into these five functions — deploying the static page to Amplify and shutting down the EC2 instance were **not** covered yet. Today starts by finishing exactly those two (Parts 1 and 3 below), with a detour in Part 2 into a second way to host the same page, then moves into new material that works directly against your Lambda functions — no Amplify page, no browser, mostly not even `curl`, needed for the rest.

By the end of this lab you will have deployed your static page to Amplify, hosted the same page on S3 instead and compared the two, shut down your EC2 instance for good, found a real cold start in your own function's metrics, practiced the actual safe-rollout pattern (publish a version, create an alias, repoint it with zero redeploy), read your functions' logs and metrics properly instead of guessing, and set up one alarm.

---

## Part 1 — Deploy the static page to Amplify

Open `labs/s3-serverless-webapp/index.html`. Compare it to `s3-v5-webapp/index.html` — same login flow, same in-memory-only `token` variable (still never `localStorage`), same list/upload UI. What's different is only *where things point*:

```js
const LOGIN_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const BUCKET_NAME_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const LIST_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const UPLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const DOWNLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
```

**Paste in each Function URL** from the five functions you already created last session (Configuration → Function URL tab, in each function's console page).

**Deploy to Amplify (console):**
1. **Amplify console → New app → Deploy without Git provider.**
2. App name: your choice.
3. Zip just `index.html` and drag-and-drop it in.
4. **Save and deploy.**

✅ **Checkpoint:** Amplify gives you a `https://<branch>.<app-id>.amplifyapp.com` URL. Visit it — the same login screen you've seen since `s3-v3-webapp`, now served from a domain that has never heard of Tomcat.

**Try the full flow:** log in, confirm the bucket name and file list load, upload a file, confirm it shows up in the list. (If you already fixed the CORS preflight and base64 bugs last session, this should just work end to end.)

**Question to consider:** open your browser's network tab while doing this. How many different domains does one page load now talk to? (Six: the Amplify domain for the page itself, plus five separate Function URL domains — one per Lambda function. Every earlier version of this app talked to exactly one domain, for everything.)

**If something fails silently** (blank list, nothing happens, no error popup): check the browser console before anything else — last session's CORS and base64 bugs are the most likely causes if you haven't fixed both yet.

---

## Part 2 — Host the same page a second way: S3 static website hosting

Same file, `index.html`, unchanged — this part is only about a different place to serve it from.

**Create a brand-new, dedicated bucket for this** — do **not** reuse the bucket `Cloud26S3ListLambda` etc. already read/write to. The next steps make every object in whatever bucket you use publicly readable by anyone, and that's only an acceptable choice for a bucket that holds nothing but this one public page. Doing it to your data bucket would also make `basin65.aux`, `test-upload.txt`, and everything else in it public — exactly what the IAM/S3 sessions spent all course teaching you to avoid.

1. **S3 console → Create bucket** — e.g. `cloud26-<yourname>-website`. Bucket names are globally unique across *all* of AWS, not just this account — if your first choice is taken, add a random suffix or number and try again.
2. **That new bucket → Properties tab → Static website hosting → Edit.**
3. **Enable**, index document: `index.html`. **Save.**
4. **Permissions tab → Block public access (bucket settings) → Edit → turn it off**, for this new bucket only.
5. **Add a bucket policy** granting `s3:GetObject` to `*` (everyone) on this new bucket's objects.
6. **Upload `index.html`** to this new bucket's root.
7. **Properties tab → Static website hosting** → copy the website endpoint URL (`http://<bucket-name>.s3-website-ap-southeast-1.amazonaws.com`).

✅ **Checkpoint:** visiting that URL shows the same login page Amplify served in Part 1 — same file, same behavior, a genuinely different hosting path underneath.

**Question to consider:** every bucket policy you've written so far this course has been scoped to one specific principal or action. This one grants `s3:GetObject` to literally everyone, unauthenticated. Is that a least-privilege mistake? (No — a static site's HTML/CSS/JS is, by definition, meant to be publicly readable by any browser. The real access control this app needs — the token check — lives entirely in the Lambda functions behind it, not in who can fetch `index.html` itself. This is exactly why you used a brand-new, dedicated bucket instead of your data bucket: public read is the right call for a bucket holding only this one public page, and the wrong call for a bucket that also holds real uploaded files.)

**Question to consider:** try the full login/list/upload flow again, this time through the S3 website endpoint instead of the Amplify URL. Notice the URL starts with `http://`, not `https://` — does that matter for this app specifically, and if so, how exactly? (The risk isn't where it first looks like it is: the `Authorization: Bearer <token>` header, on its way to a Lambda Function URL, really is encrypted either way — Function URLs are HTTPS-only no matter how the page was served. The actual problem is earlier: over plain HTTP, nothing guarantees the HTML/JS your browser receives is the real, unmodified file you uploaded. Someone on the network between you and S3 — a hostile Wi-Fi hotspot, say — could tamper with that response in transit, slipping in one extra line of JavaScript that copies your token to their own server the moment you type it, before your real `fetch()` call to the Function URL even happens. The page would look completely normal. Serving the page itself over HTTPS — what Amplify gives you for free — is what rules this out, by guaranteeing the browser only ever runs the code you actually deployed.)

**Note — the real-world fix for S3's HTTPS gap is CloudFront, not giving up on S3 entirely.** S3's static website endpoint can only ever speak plain HTTP — that's a hard limit of the feature. The standard production pattern instead puts **CloudFront** (AWS's CDN) in front of the same bucket: the browser talks to CloudFront over HTTPS, with AWS managing that certificate for you, and CloudFront fetches the file from S3 behind the scenes. That closes the exact tampering gap from the question above.

**Question to consider:** does that mean CloudFront + S3 makes Amplify unnecessary for a page like this one? (Not quite. CloudFront + S3 closes the HTTPS gap, but trades it for *more* to configure, not less — now there are three things to keep straight instead of one: the bucket, its policy, and a CloudFront distribution with its own cache settings and certificate. It also loses S3 alone's main appeal: CloudFront caches aggressively, so uploading a new `index.html` doesn't necessarily take effect right away — you'd typically have to manually invalidate the cache. CloudFront + S3 is a legitimate way to make plain S3 hosting production-ready, but at that point you're reassembling, piece by piece, most of what Amplify already gives you in one step.)

**Closing question to consider:** when would you actually reach for plain S3 hosting over Amplify, and when wouldn't you? A fair answer: S3 alone is fine for a genuinely static, low-stakes page where HTTPS doesn't matter and no deploy pipeline is needed. For *this specific app* — a login page sending a real credential on every request — Amplify's free HTTPS and simpler redeploys are a meaningful enough advantage to be the right default here.

---

## Part 3 — Shut down your EC2 instance for good

```bash
aws ec2 stop-instances --instance-ids <your-instance-id> --profile CTO
```

This isn't "stop it like every previous session, start it again next time." Nothing in this app needs this instance anymore. It can stay stopped permanently.

**Question to consider:** think back across every session this course. What did you actually gain from going serverless, and what did it cost you to get there? Try to name at least one concrete thing on each side — not just "serverless is more modern." (Gain: Lambda functions were always billed only per invocation, never for sitting idle — that didn't change here. What changed is that the EC2 instance, the one piece of this app that *was* billed by the hour regardless of use, is gone — so now nothing in the app is billed while idle, period. Also: no server to patch or SSH into, no "did I remember to stop the instance" habit. Cost: CORS configuration everywhere, a hand-rolled upload format instead of a plain HTML form, `AuthCheck` duplicated five times instead of centralized once, five separate things to deploy instead of one `.war`, and a harder debugging story — which of six domains failed, this time? The CORS and base64 bugs from last session are live examples of that cost, not hypothetical ones.)

**Closing question to consider:** for an app this small — one small team, light traffic — was going serverless actually worth it? There's no single right answer expected, but a fair, honest one might be "not obviously" — this architecture is objectively more complicated to reason about than one Tomcat process was. Serverless tends to pay off more clearly at a different scale (traffic that spikes unpredictably, many teams each owning separate pieces, needing to scale one endpoint without scaling everything else) — not automatically, just because it's newer.

---

## Part 4 — Read your logs and metrics properly

**Add a real log line, so there's something fresh to actually find.** Last session's bugfix logging in `LoginFunction` was removed once the bug was fixed — temporary debug output doesn't belong in working code long-term — so there's nothing informative in the logs right now. Add one line back, deliberately, to `S3ListFunction.java` (`labs/s3-serverless-lambdas/S3ListFunction/src/main/java/edu/cloudcomputing26/lambda/S3ListFunction.java`), right after the `ListObjectsV2Response` comes back, before the JSON-building loop:

```java
context.getLogger().log("DEBUG: returned " + listResponse.contents().size() + " objects for bucket " + BUCKET_NAME);
```

**Build and redeploy:**
```bash
cd labs/s3-serverless-lambdas/S3ListFunction && mvn clean package
```
Upload the new jar to `Cloud26S3ListLambda` via the console, then trigger it a couple of times (`curl` the Function URL, or the Test tab) so there's fresh log output to query.

**Find that log line, via Logs Insights instead of scrolling:**
1. CloudWatch console → **Logs → Logs Insights**.
2. Pick the log group `/aws/lambda/Cloud26S3ListLambda`.
3. Run:
```
fields @timestamp, @message
| filter @message like /DEBUG/
| sort @timestamp desc
| limit 20
```
✅ **Checkpoint:** you should see the `DEBUG: returned N objects...` line from the invocations you just triggered, each tagged with its own request ID.

**Question to consider:** this `DEBUG` line is itself exactly the kind of thing worth removing once you're done, same as last session's bugfix logging — unless it's deliberately promoted to permanent, structured logging. What's the cost of leaving ad hoc debug lines in deployed code forever? (Noisier logs, harder signal-to-noise the next time you're actually debugging something — this lab won't make you clean it up, but a real system would expect it.)

**Question to consider:** last session's base64 bug in `LoginFunction` never threw an exception — it ran to completion and returned a normal `401`. Would it show up in the **Errors** metric on that function's Monitor tab? (No — Errors counts crashes/uncaught exceptions, not wrong-but-successful responses. This is exactly why the fix needed temporary log lines, not just a glance at the metrics dashboard.)

**Look at the built-in metrics** (no logging code required — Monitor tab → Metrics, for any function):
- **Invocations** — how many times it ran.
- **Errors** — crashes only.
- **Duration** — how long each call took (Part 7 comes back to this one specifically, for cold starts).
- **Throttles** — calls rejected outright for having too many running at once (Part 8).

**Set one alarm:**
1. CloudWatch console → **Alarms → Create alarm**.
2. Metric: `Errors` on one function, **Sum**, 5-minute period.
3. Condition: greater than `0`.
4. Skip the notification step — just create it.

✅ **Checkpoint:** the alarm appears in your Alarms list.

---

## Part 5 — Publish a version and create an alias

Every function you've built this course has always had exactly one thing called `$LATEST` — whatever code you most recently uploaded. There's never been a way to keep the old code around, or test new code before it becomes the only code. That changes today.

**Publish a version:**
1. Lambda console → `Cloud26S3ListLambda` → **Actions → Publish new version**.
2. Give it a description (e.g. "working version from Going Serverless").
3. Note the version number (likely `1`).

This creates an **immutable snapshot** — exactly the code and config `$LATEST` had at that instant, frozen forever under that number. `$LATEST` itself didn't change and is still editable.

✅ **Checkpoint — invoke the specific version directly:**
```bash
aws lambda invoke --function-name Cloud26S3ListLambda:1 --payload '{}' /tmp/out.json --profile CTO
cat /tmp/out.json
```

**Create an alias pointing at it:**
1. **Aliases tab → Create alias.**
2. Name: `live`. Version: `1`.
3. **Create.**

**Question to consider:** why would you ever call `Cloud26S3ListLambda:live` instead of just calling `Cloud26S3ListLambda` (which always means `$LATEST`) directly? (Because `$LATEST` is mutable — anyone can upload new code to it at any time. `live` is a name *you* control, and it only changes when you deliberately repoint it. A Function URL or any other caller using `live` is protected from an in-progress, possibly broken, upload to `$LATEST`.)

---

## Part 6 — Practice the actual safe-rollout pattern

This is the point of Part 5 — do the whole cycle, don't skip to the end.

1. Make a small, visible code change to the `S3ListFunction` class — e.g. add one extra JSON field to the response — something a `curl` call will clearly show in its response body. (A log-only change wouldn't work here: the checkpoints below diff the actual response, not the logs. The `DEBUG` line you added in Part 4 can stay in place; it doesn't affect the response body either way.)
2. Build and upload the new jar to `Cloud26S3ListLambda` (this updates `$LATEST` only — version `1` and the `live` alias are untouched):
```bash
cd labs/s3-serverless-lambdas/S3ListFunction && mvn clean package
```
Upload the new `target/s3-list-function.jar` via the console as usual.

✅ **Checkpoint — confirm `$LATEST` and `live` now disagree:**
```bash
aws lambda invoke --function-name Cloud26S3ListLambda --payload '{}' /tmp/latest.json --profile CTO
aws lambda invoke --function-name Cloud26S3ListLambda:live --payload '{}' /tmp/live.json --profile CTO
diff /tmp/latest.json /tmp/live.json
```
`diff` should show a real difference — `$LATEST` has your new change, `live` (still version 1) does not.

3. **Publish a new version** from this updated `$LATEST` (Actions → Publish new version) — note its number (likely `2`).
4. **Repoint the `live` alias to version 2** (Aliases tab → edit `live` → change version to `2` → Save).

✅ **Checkpoint — `live` now matches `$LATEST`:**
```bash
aws lambda invoke --function-name Cloud26S3ListLambda:live --payload '{}' /tmp/live2.json --profile CTO
diff /tmp/latest.json /tmp/live2.json
```
Should show no difference now.

**Question to consider:** this course has now shown two different ways to change a Lambda function's behavior without anyone redeploying code at the moment it takes effect: rotating `CheckTokenIsValid`'s `TOKEN` environment variable (AWS Lambda session), and repointing the `live` alias just now. Are these the same kind of change? (No. An environment variable changes *configuration a function reads* — the code's version never changes. An alias changes *which version of the code itself* gets called. Worth being able to say which one you'd reach for for a given problem, not just that both exist.)

---

## Part 7 — Find a cold start in your own metrics

A Lambda function doesn't exist as a running process between invocations — when a request arrives with no already-running environment available, AWS has to provision one from scratch (start the environment, load your code, start the JVM, run your class's static initialization) before your `handleRequest` method ever runs at all. That's a **cold start**. A request to an environment that's already warm skips straight to `handleRequest`.

**Find one, two ways.**

**First, the quick visual version, using the exact Duration metric from Part 4:** Lambda console → pick any of your five functions → **Monitor** tab → **Metrics** → **Duration**. Look for the first invocation after a period of inactivity — compare its duration to a second invocation you trigger immediately afterward (via `curl`, or Test tab). This graph is a timeseries of every invocation's duration, so a cold start shows up as a visible spike — but with a short time window, it's not always obvious whether a given spike is really a cold start or just a slower invocation for some other reason.

**Second, the exact version — Lambda's own `REPORT` log line, via Logs Insights:** every invocation, cold or warm, automatically logs one `REPORT` line with no code required — this isn't the `DEBUG` line from Part 4, it's built into the Lambda runtime itself. A cold start's `REPORT` line includes an extra field, `Init Duration`, that's simply absent on a warm invocation. That's an exact, unambiguous signal, not a guess from a graph.

1. Trigger a function after it's been idle a while (a cold start), then immediately again (a warm invocation) — `curl` the Function URL twice, a few seconds apart, after not touching it for a few minutes.
2. CloudWatch console → **Logs → Logs Insights** → pick that function's log group (e.g. `/aws/lambda/Cloud26S3ListLambda`).
3. Run:
```
fields @timestamp, @duration, @initDuration, @billedDuration
| filter ispresent(@initDuration)
| sort @timestamp desc
| limit 20
```
✅ **Checkpoint:** the row for your first (cold) invocation appears, with a real `@initDuration` value — the JVM-startup-plus-static-initializer cost, separate from `@duration` (the handler's own run time) and `@billedDuration` (what you're actually charged for). Run the query again with `filter not ispresent(@initDuration)` instead, and confirm your second (warm) invocation shows up there instead, with no `@initDuration` field at all.

**Question to consider:** compare the `@initDuration` value from your cold invocation to the `@duration` value from your warm one. Roughly how many times slower was the cold start? (Answers will vary, but a small Java function's cold start is commonly several hundred ms to low seconds, while the same warm logic typically runs in single-digit to double-digit ms — often a 10x+ difference or more, driven mostly by JVM startup cost.)

**Question to consider:** does `S3ListFunction`'s shared, `static` `S3Client` field help cold starts, warm invocations, both, or neither? (Warm invocations only — on a cold start, that client still has to be built as part of the environment's startup, `static` or not. `static` just means a *warm* environment doesn't rebuild it on every single call.)

---

## Part 8 — Concurrency limits and why this lab doesn't turn on Provisioned Concurrency

**Question to consider:** if 50 requests hit one of your functions at the exact same instant, what happens to the 51st, 52nd, etc., if your account's concurrency limit is lower than that? (They're **throttled** — rejected immediately with an error, not queued and delayed. A Lambda function under too much simultaneous load fails fast; it doesn't just get slower the way an overloaded server typically does.)

**Provisioned Concurrency** keeps a chosen number of execution environments permanently warm, so even a request after a long idle period skips the cold start from Part 7 entirely. **This lab does not turn it on**, and here's why stated plainly: it's billed for the whole time it's configured, whether or not the function is actually invoked — the exact always-on cost Lambda's standard pricing model exists to avoid. For a handful of classroom `curl` calls, there's nothing to gain and a real cost to pay.

**Question to consider:** a common DIY alternative to Provisioned Concurrency is scheduling an EventBridge rule to invoke a function every 5 minutes with a "dummy" payload the handler recognizes and ignores, purely to keep one environment warm. Is this actually free, and does it solve the same problem? (Not quite either. It's not free — every ping is still a real, billed invocation, and it runs continuously, including whenever nobody's actually using the app, which is a smaller version of the exact "paying for idle capacity" problem this section is about. More importantly, it only keeps *one* environment warm — if two real requests arrive at the same instant, Lambda still has to cold-start a second environment regardless of how often you've been pinging. It's a real, cheaper alternative for a function that only ever handles one request at a time, but it's not a guarantee the way Provisioned Concurrency is, and it doesn't help with concurrent load at all.)

**Closing question to consider:** pick one of today's three ideas (cold starts, versions/aliases, concurrency limits) and say concretely how you'd notice it mattering in your own application, using only what you learned today — no new code. (A fair answer points at a specific metric or behavior: Duration spikes for cold starts, `$LATEST` vs. an alias disagreeing for versions, Throttles climbing for concurrency — not just restating the definition.)
