# Week 10 Report: Orchestrating Services with Step Functions

## 1. Goal

Week 9 left a gap on purpose. `S3UploadFunction` wrote the file to S3 and then wrote the note to DynamoDB as two separate calls. If `putObject` succeeded and `putItem` failed, the file stayed in the bucket with no note, and nothing cleaned it up.

Week 10 Part 2 closes that gap with a Standard Step Functions state machine. The Lambda still uploads the file. After that, a state machine named `FileNotesOrchestration` writes the note, retries the DynamoDB call, and deletes the S3 object if every retry fails. This is a saga: detect the failure after the S3 write, then run a second call to undo it. It is not an atomic transaction across S3 and DynamoDB.

Part 1 of the lab (the same retry and delete written by hand inside the Lambda) was not built. This report covers Part 2 only.

Architecture after week 10:

```
Browser (index.html, unchanged from week 9)
   |-- Login            -> LoginFunction
   |-- Bucket name      -> S3GetBucketNameFunction
   |-- List             -> S3ListFunction     -> S3 ListObjectsV2 + DynamoDB GetItem
   |-- Upload (+ note)  -> S3UploadFunction   -> S3 PutObject
   |                         then StartExecution + poll DescribeExecution
   |                              |
   |                              v
   |                        FileNotesOrchestration (Standard)
   |                           |-- dynamodb:putItem  (retry 3 times, backoff 2.0)
   |                           |-- on success: Succeed
   |                           '-- on failure: s3:deleteObject, then Fail
   '-- Download         -> S3DownloadFunction -> S3 GetObject
```

## 2. What was done

### 2.1 State machine `FileNotesOrchestration`

Definition: `orchestrating-services-part2-lab-starting-code/labs/s3-serverless-lambdas-orchestration/FileNotesOrchestration.asl.json`.

- Type: Standard, so each execution keeps a graph and an event history in the console.
- Region `ap-southeast-1`. Name must be `FileNotesOrchestration`.
- Input is JSON only: `fileKey`, `note`, `bucketName`. The file bytes stay out of the state machine because an execution payload is capped at 256 KB.
- `SaveNoteToDynamoDB` calls `dynamodb:putItem` on table `FileNotes` directly. Step Functions does this itself. There is no second Lambda.
- Retry: `IntervalSeconds` 1, `MaxAttempts` 3, `BackoffRate` 2.0. The waits before the three retries are about 1s, 2s, and 4s.
- If those retries still fail, `Catch` goes to `DeleteOrphanedUpload` (`s3:deleteObject`).
- Delete succeeds: terminal state `SaveFailed` (`SaveNoteFailed`). The object is gone.
- Delete also fails: terminal state `SaveFailedCompensationAlsoFailed` (`SaveNoteFailedAndCompensationFailed`). The file is still in S3 with no note. The history names that case instead of hiding it.

### 2.2 IAM

Account `353842237220` and bucket `cloud26-phucnhan-bucket` are filled in. These are the same values as week 9.

| Policy | Attached to | Permissions |
|---|---|---|
| `resources/stepfunctions-filenotes-orchestration-execution-policy.json` | New role `FileNotesOrchestrationRole` (trust `states.amazonaws.com`) | `dynamodb:PutItem` on `FileNotes`, `s3:DeleteObject` on `cloud26-phucnhan-bucket/*` |
| `resources/cto-lambda-stepfunctions-execution-policy.json` | Execution role of `Cloud26S3UploadFunction` | `states:StartExecution` on the state machine, `states:DescribeExecution` on its executions |

`S3ListFunction` still needs `dynamodb:GetItem`. If upload and list share one execution role, the week 9 DynamoDB policy stays on that role. Removing it would make list return 500. `PutItem` left unused on a shared role does not change list. `s3:DeleteObject` belongs on the state machine role, not on the Lambda.

`Cloud26S3UploadFunction` timeout is set to 30 seconds. The failure path waits about 7 seconds inside Step Functions, and the Lambda polls for up to 20 seconds. The previous 3 second timeout would cut the HTTP call off before the execution reached `FAILED`. Handler stays `edu.cloudcomputing26.lambda.S3UploadFunction::handleRequest`.

### 2.3 Lambda code

Only `S3UploadFunction` changed. `Cors.java` and `AuthCheck.java` from the Part 2 starting code are reused. Login, list, download, get-bucket-name, and `index.html` are the week 9 deployments and were not rebuilt.

What the upload handler still does, unchanged from week 9:

- CORS preflight, bearer token check, `X-Filename`, base64 body, `X-Note` decoded with `URLDecoder`.
- `S3.putObject` to `cloud26-phucnhan-bucket`.

What it does instead of `DynamoDB.putItem`:

- Builds `{"fileKey","note","bucketName"}` and calls `StartExecution` on `arn:aws:states:ap-southeast-1:353842237220:stateMachine:FileNotesOrchestration`.
- Polls `DescribeExecution` every 500 ms, at most 40 times.
- `SUCCEEDED` returns 200 with the same body as week 9, including the `Note:` line.
- `FAILED`, `TIMED_OUT`, or `ABORTED` returns 500. The body includes the state machine error (`SaveNoteFailed` or `SaveNoteFailedAndCompensationFailed`) and its cause.
- If `StartExecution` itself throws, the file is already in S3 and no execution exists to delete it. The 500 says the orchestration failed to start.
- `pom.xml` replaces the `dynamodb` dependency with `software.amazon.awssdk:sfn`.

`escapeJson` is the week 9 list-function helper, plus escapes for newline, carriage return, and tab, because the execution input is a hand-built JSON string.

### 2.4 Build

`mvn clean package` in the Part 2 `S3UploadFunction` module succeeded. The jar to upload is the shaded file, about 15 MB:

`orchestrating-services-part2-lab-starting-code/labs/s3-serverless-lambdas-orchestration/S3UploadFunction/target/s3-upload-function.jar`

`original-s3-upload-function.jar` is not the one to deploy.

## 3. What changed compared with week 9

| | Week 9 | Week 10 Part 2 |
|---|---|---|
| User-visible feature | Upload a file with a note, list shows the note | Same. The browser still calls one upload URL and reads one list URL. |
| Who writes the note | `S3UploadFunction` calls `PutItem` | State machine calls `PutItem` directly |
| If DynamoDB fails after S3 succeeds | 500, text "File uploaded, but saving its note failed". The file remains, note is empty. | Retry 3 times. If still failing, delete the S3 object. 500 with `SaveNoteFailed`. The file is gone. |
| If the cleanup delete also fails | No cleanup existed | 500 with `SaveNoteFailedAndCompensationFailed`. The orphan is visible in the execution history. |
| Retry state | None. One attempt inside one Lambda invocation. | Held by Step Functions, outside the Lambda. A killed invocation does not erase the execution history. |
| New AWS service | DynamoDB table `FileNotes` | Step Functions state machine `FileNotesOrchestration` |
| Upload Lambda permissions | `s3:PutObject` and `dynamodb:PutItem` | `s3:PutObject`, `states:StartExecution`, `states:DescribeExecution` |
| Who may delete the object | Nobody in the app | The state machine role, and only as compensation |
| List / login / download / page | Changed in week 9 (list reads notes, page has a note field) | Not changed |
| DynamoDB item shape | `fileKey` (partition key, String), `note` (String) | Same table, same two attributes |
| Timeout of upload Lambda | Default (3 seconds was enough for one `PutItem`) | 30 seconds, because the handler waits on the execution |

Week 9 accepted the orphan because a note is cosmetic. Week 10 treats that orphan as the bug. Both versions can still end with a file and no note: week 9 whenever `putItem` fails, week 10 only when the compensating `deleteObject` fails too, or when `StartExecution` never starts. Neither version is a real cross-service transaction. `TransactWriteItems` still cannot cover an S3 write.

## 4. How the three checks work

Same upload command three times. Token is the password already used on the week 9 page. PowerShell must call `curl.exe`.

Upload URL (unchanged): `https://bdps5pr2klxsv4gorlnzfovtiq0xfhtt.lambda-url.ap-southeast-1.on.aws/`

List URL (unchanged): `https://ewxuwlkar6dqdisrydzopfcuia0ugdru.lambda-url.ap-southeast-1.on.aws/`

1. `TableName` is `FileNotes`. POST `sfn-demo.txt` with note `works fine`. Expect `200` and `Note: works fine`. List shows `"note":"works fine"`. The execution graph is `SaveNoteToDynamoDB` then `SaveSucceeded`.
2. Edit the state machine so `TableName` is `FileNotes-broken`. Repeat the same POST. Expect `500` and `SaveNoteFailed`, after roughly 1s + 2s + 4s of retries. The graph goes through `DeleteOrphanedUpload` to `SaveFailed`. List no longer contains `sfn-demo.txt`.
3. Set `TableName` back to `FileNotes`. The same POST returns `200` again, and list shows the file and the note.

## 5. Related files

- `orchestrating-services-session-student-lab.md`: the lab handout.
- `orchestrating-services-part2-lab-starting-code/labs/s3-serverless-lambdas-orchestration/FileNotesOrchestration.asl.json`: the state machine.
- `orchestrating-services-part2-lab-starting-code/labs/s3-serverless-lambdas-orchestration/S3UploadFunction/`: the upload Lambda that starts and polls it.
- `orchestrating-services-part2-lab-starting-code/resources/stepfunctions-filenotes-orchestration-execution-policy.json`: role of the state machine.
- `orchestrating-services-part2-lab-starting-code/resources/cto-lambda-stepfunctions-execution-policy.json`: extra permissions on the upload Lambda role.
- `week9/WEEK9_REPORT.md`: the database layer this session starts from.
