# Lab: Database Layer with DynamoDB

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
| NoSQL | Not-only/Not-only-SQL (a non-relational database model) |

**You will need:** the five Lambda functions and the Amplify page from "Going Serverless" / "More About Lambdas," already deployed and working. Today's starting point is a **fresh copy** of both, not an edit of last session's — `labs/s3-serverless-lambdas-db/` and `labs/s3-serverless-webapp-db/`. Your existing `Cloud26*` functions and Amplify app from before today are untouched; today's steps build and deploy new jars from the `-db` copies on top of them (same deployed function names, new code).

**Before you start:** today's parts need IAM permissions beyond what earlier sessions granted — creating/reading the new DynamoDB table directly as `CTO` (Parts 1–2), and letting your Lambda functions themselves read/write that table (Part 3). If any step below returns `AccessDenied`, this is why — check with your instructor rather than trying to work around it.

By the end of this lab you will have created a DynamoDB table, written to it and read from it by hand from the CLI, wired your upload function to save a note and your list function to read it back, and tried the whole thing end to end in the browser — including thinking through what happens when one of the two writes succeeds and the other doesn't.

---

## Part 1 — Create the `FileNotes` table

1. **DynamoDB console → Create table.**
2. **Table name:** `FileNotes`.
3. **Partition key:** `fileKey` (type: String).
4. **Table settings:** leave on **default (on-demand)** capacity mode — no throughput numbers to guess at, billed per request actually made, same idea as every Lambda function being billed per invocation rather than by the hour.
5. **Create table.**

✅ **Checkpoint:** the table's status shows `Active`, with 0 items.

**Question to consider:** unlike a SQL table, DynamoDB asks you to commit to your main access pattern up front, as the partition key — you can't easily add an efficient "look this up a totally different way" later without more work (a secondary index). What's the one access pattern this table actually needs to support in this app, and does `fileKey` alone cover it? (Exactly one: given an S3 object's key, find its note. `fileKey` as the sole partition key covers that completely — there's no second lookup this app needs today.)

**Question to consider:** this lab uses DynamoDB rather than a relational database (AWS RDS running MySQL, say). Try to name at least one concrete reason for and against that choice, not just "DynamoDB is what the lab uses."
- **For DynamoDB here:** no server at all, matching every other piece of this app since "Going Serverless" — RDS, even on its smallest instance, is a server billed by the hour the whole time it exists, the exact always-on cost this course keeps steering away from. On-demand billing (what you just picked in step 4) also matches how every Lambda function has already been billed all course: pay per request, nothing while idle. And the access pattern here — one key, one item, no joins — is exactly what DynamoDB is built for.
- **Against DynamoDB here:** there's no ad hoc querying later. You can't write a new `WHERE note LIKE '%draft%'`-style query against this table without having planned for it in advance (a secondary index, not used in this lab); a relational database would just answer that with an ordinary query, written whenever the question comes up. If this app ever needed to join notes against some other table (say, who uploaded each file), DynamoDB would need that data duplicated into the same item or fetched as a second call — RDS would just join the two tables.
- **The honest scale point:** at this lab's size — a handful of files, one classroom — RDS's smallest instance would also have worked fine, and its always-on cost would have been small in absolute terms. The real reason for DynamoDB here is the architecture fit, not that RDS would have been too expensive to run for a lab this small.

---

## Part 2 — PutItem and GetItem by hand, from the CLI

Before touching any code, see both operations happen in isolation.

```bash
aws dynamodb put-item \
  --table-name FileNotes \
  --item '{"fileKey": {"S": "example.png"}, "note": {"S": "screenshot for the slides"}}' \
  --profile CTO
```
✅ **Checkpoint:** no output — DynamoDB's CLI convention for success, same as some `s3 cp` calls earlier this course.

```bash
aws dynamodb get-item \
  --table-name FileNotes \
  --key '{"fileKey": {"S": "example.png"}}' \
  --profile CTO
```
✅ **Checkpoint:** the item comes back as JSON, each value wrapped in a type tag, e.g. `{"S": "screenshot for the slides"}`.

**Question to consider:** why does every value need that `{"S": ...}` wrapper, when a column's type in a SQL table is just declared once, in the schema? (DynamoDB is schemaless across items — two items in the same table can have completely different attributes, so there's no fixed per-column type anywhere for a value to inherit. The type has to travel with the value itself, every time.)

**Now look up a key that was never written:**
```bash
aws dynamodb get-item \
  --table-name FileNotes \
  --key '{"fileKey": {"S": "nonexistent.txt"}}' \
  --profile CTO
```
✅ **Checkpoint:** an empty response — no error, no "not found" message. Keep this in mind for Part 3: a missing item isn't an exception your code catches, it's just an empty result your code has to check for itself.

---

## Part 3 — Wire the database into the app

Open `labs/s3-serverless-lambdas-db/S3UploadFunction/src/main/java/edu/cloudcomputing26/lambda/S3UploadFunction.java` and `labs/s3-serverless-lambdas-db/S3ListFunction/src/main/java/edu/cloudcomputing26/lambda/S3ListFunction.java`. Both already have today's changes written in — read through them rather than writing new code, and make sure you can explain each piece before moving on.

**Question to consider, before reading further:** `S3UploadFunction` is about to do **two** different writes — one to S3, one to DynamoDB — inside **one** Lambda invocation, triggered by **one** `fetch()` call from the browser. An alternative design would have the browser make two separate calls instead: upload the file via one Function URL, then call a second, brand-new "save note" Lambda afterward. Try to name one concrete reason for each approach before reading the answer below.
- **For one Lambda doing both writes (what this lab built):** one round trip from the browser instead of two, for something the user experiences as a single action. The frontend stays as simple as every earlier version — one `fetch()`, one `.then()` — instead of having to sequence two calls and decide what "the file uploaded but the note didn't" should look like to the user. The token only gets checked once per upload, not twice.
- **For two separate Lambdas instead:** a partial failure becomes visible and independently fixable — if just the note-save step failed, the browser could retry *only* that step, instead of the file-upload-and-note being bundled into one all-or-nothing call. It would also make the note-write reusable on its own later (e.g. editing a note without re-uploading the file), without that logic being buried inside the upload handler.
- **The actual tradeoff:** bundling both writes into one Lambda call doesn't make the two-writes-aren't-atomic problem from Part 4 go away — it just makes it less visible from the outside, since the caller sees one status code either way. Splitting them wouldn't fix that either, but it would at least expose the two steps separately.

**Closing question to consider:** if this app later needed a *third* thing to happen on every upload (say, generating a thumbnail), would you keep adding it straight into `S3UploadFunction`, or start splitting responsibilities into separate functions? There's a real point where "one function doing everything inline" stops being the simpler choice — the same kind of judgment call you've already seen with `AuthCheck` being duplicated across every function instead of centralized, and S3 vs. Amplify for hosting one file.

**Trace the note from the browser first.** In `labs/s3-serverless-webapp-db/index.html`:
```js
const note = document.getElementById("noteInput").value;

fetch(UPLOAD_URL, {
  method: "post",
  headers: Object.assign(
    { "X-Filename": file.name, "X-Note": encodeURIComponent(note) },
    authHeaders()),
  body: file
})
```

**Question to consider:** `X-Filename` is sent as-is, but the note is run through `encodeURIComponent` first. Why? (HTTP header values can't safely carry arbitrary text — newlines terminate a header, and plenty of non-ASCII characters aren't valid in a raw header value at all. A note is free text someone actually writes sentences into, so it needs escaping; a typed filename is unlikely to ever hit that problem, which is why it's been left alone.)

**In `S3UploadFunction.java`, find where the header is decoded back:**
```java
String encodedNote = headers.get("x-note");
String note = (encodedNote == null) ? "" : URLDecoder.decode(encodedNote, StandardCharsets.UTF_8);
```
**Then, right after the existing S3 `putObject` call:**
```java
PutItemRequest putItemRequest = PutItemRequest.builder()
        .tableName(TABLE_NAME)
        .item(Map.of(
                "fileKey", AttributeValue.fromS(key),
                "note", AttributeValue.fromS(note)))
        .build();

DYNAMO_DB.putItem(putItemRequest);
```
**Question to consider:** this is the same `PutItem` call you just ran by hand from the CLI in Part 2. What's the Java-side equivalent of the CLI's `{"S": "..."}` wrapping? (`AttributeValue.fromS(...)` — building the same tagged value, just as a Java object instead of typing it out as JSON.)

**In `S3ListFunction.java`, find the read side:**
```java
private static String noteFor(String key) {
    GetItemRequest getRequest = GetItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(Map.of("fileKey", AttributeValue.fromS(key)))
            .build();

    GetItemResponse getResponse = DYNAMO_DB.getItem(getRequest);
    if (!getResponse.hasItem()) {
        return "";
    }

    AttributeValue note = getResponse.item().get("note");
    return (note == null) ? "" : note.s();
}
```
**Question to consider:** why does this method check `hasItem()` instead of just calling `.item().get("note")` directly? (Because a file that existed before this session, or was uploaded before this code ever ran, has no item in `FileNotes` at all — exactly the "empty result, no error" case from Part 2's last checkpoint. Skipping the check would still technically work here since `.get("note")` on a missing item returns `null` too, but checking `hasItem()` first says what's actually going on, instead of relying on a coincidence of the API's behavior.)

**Now look at where `noteFor(key)` actually gets called**, a few lines up in the same file — once per object, inside the loop that builds the JSON response. **Question to consider:** if this bucket held 10,000 files instead of a handful, how many total AWS calls would one request to `S3ListFunction` make? (1 `ListObjectsV2` call, plus 10,000 separate `GetItem` calls — one per file, made one at a time in a loop, all inside that single Lambda invocation. This shape — one "get the list" call followed by one more call *per item in that list* — has a name: **N+1**. It's the same cost whether the "+1" and the "N" calls go to DynamoDB, as here, or to any other per-item lookup.)

**Question to consider, tying this back to Part 3's earlier design question:** `S3ListFunction` is making the same architectural choice `S3UploadFunction` made — one Lambda making multiple AWS calls itself, instead of splitting the work into separate Lambdas that invoke each other — and for the same reasons: simpler code, one request from the browser, nothing new to secure. Does splitting "fetch the notes" into its own Lambda, called once by `S3ListFunction`, fix the N+1 cost you just found? (No — the N separate `GetItem` calls still have to happen somewhere; moving them into a different Lambda doesn't reduce how many there are, it just moves where they run. "Fewer Lambdas" and "fewer downstream calls" are two different questions — the thing that would actually reduce the call count is `BatchGetItem`, which fetches up to 100 items in one request, not a change to which function does the calling.)

**Build and deploy both functions:**
```bash
cd labs/s3-serverless-lambdas-db/S3UploadFunction && mvn clean package
cd labs/s3-serverless-lambdas-db/S3ListFunction && mvn clean package
```
Upload each new `target/*.jar` to its existing deployed function (`Cloud26S3UploadFunction`, `Cloud26S3ListLambda`) via the console, same as every earlier redeploy this course.

**Before testing, make sure the new execution-role policy is attached** (`resources/cto-lambda-dynamodb-execution-policy.json`, with `<ACCOUNT_ID>` filled in) to the Lambda execution role these two functions use — ask your instructor if you're not sure whether this has been done yet. This is a **different identity** than `CTO` — `CTO` having DynamoDB permissions (from Part 1–2) doesn't give the *function itself* those permissions; the execution role is what the function assumes while running, the same distinction "Security of vs. in the Cloud" drew between your EC2 instance's role and your own IAM user.

✅ **Checkpoint:**
```bash
curl -X POST <UPLOAD_URL> \
  -H "Authorization: Bearer <your token>" \
  -H "X-Filename: db-test.txt" \
  -H "X-Note: a%20test%20note" \
  --data-binary "hello"
```
Response includes `Note:   a test note`.
```bash
curl <LIST_URL> -H "Authorization: Bearer <your token>"
```
✅ **Checkpoint:** the JSON for `db-test.txt` includes `"note":"a test note"`; every older file shows `"note":""`.

---

## Part 4 — Try it end to end, what "not atomic" means here, and a delete exercise

**Deploy `labs/s3-serverless-webapp-db/index.html` to Amplify** (or wire its Function URLs into your existing app, if that's simpler) and try the full flow: log in, upload a file with a note typed into the new field, confirm the file list shows that note in its own column.

**Question to consider:** `S3UploadFunction` makes two separate calls — `S3.putObject`, then `DYNAMO_DB.putItem`. What happens if the first succeeds and the second one fails? (The file is genuinely, fully uploaded to S3 — but has no note, because the second call never finished. Nothing links these two calls together; there's no mechanism here making both succeed or both roll back as one unit.)

**Question to consider:** DynamoDB has a feature called Transactions (`TransactWriteItems`) that makes multiple DynamoDB writes atomic with each other. Would that fix this specific problem? (No — one of the two writes here is to S3, not DynamoDB. Transactions only span writes within DynamoDB itself; nothing here spans both services at once.)

**Closing question to consider:** this lab accepts the small risk of an upload succeeding with no note, rather than building a fix (a retry, or a job that reconciles S3 objects with missing DynamoDB items). Is that an oversight, or a reasonable call? Would your answer change if this were storing something more consequential than a cosmetic note — a payment record, say, or a permissions grant? (A reasonable call for a note field: worst case, it stays blank until someone re-saves it. The right amount of engineering effort to prevent a partial failure scales with what's actually at stake — which is the real point, more than whether this specific case needed fixing.)

**Question to consider, the same problem from the other direction:** everything above assumed a file gets created with no note. What happens to a note in `FileNotes` if the S3 object it belongs to gets *deleted* instead? (Nothing — `FileNotes` has no idea S3 exists at all. DynamoDB doesn't enforce any relationship between `fileKey` and an actual S3 object; delete the file, and its note sits there forever, orphaned. This is the same two-systems problem as upload, just in the other direction — except this one is permanent, not a window a retry could close.)

**Note:** as this app is currently set up, nobody using it can actually delete a file — `CTO` has no `s3:DeleteObject` permission on the data bucket, same least-privilege pattern as every other policy this course has attached. So the orphaned-note problem above can't happen through the app today. That's about to change, as an exercise.

### Stretch exercise — add a delete button (not required to complete this lab)

Add a button to each row in the file list, so that clicking it deletes that file from S3 **and** deletes its corresponding item from `FileNotes` — both, or neither, never just one.

Think through, before writing any code:
1. **What new Lambda function (or new branch in an existing one) does this need?** It will need `s3:DeleteObject` and `dynamodb:DeleteItem` permissions — permissions this course has deliberately never granted `CTO` before now. Ask your instructor before assuming you can just add them yourself.
2. **What order should the two deletes happen in, and why does it matter?** For upload, S3-then-DynamoDB was the only order that made sense — there's no note to attach until the file exists. For delete, you get to choose: if you delete the DynamoDB item first and the S3 delete then fails, you're left with a file that has no note — the exact same, already-accepted gap from Part 4's upload discussion, not a new problem. If you delete S3 first and the DynamoDB delete then fails, you're left with the orphaned-note problem you just identified above — the one this exercise exists to avoid. **Which order would you pick, and why?**
3. **Is there an AWS feature that makes this fully atomic?** (No — DynamoDB Transactions only span multiple DynamoDB writes with each other; there's no AWS primitive that makes an S3 delete and a DynamoDB delete happen as one atomic unit together. The ordering choice in question 2 is a real mitigation, not a true guarantee.)
