# Week 9 Report: Database Layer with DynamoDB

## 1. Goal

Add a database layer (DynamoDB) to the serverless app built in weeks 7 and 8 (5 Java Lambda functions + a static web page + S3). Every file uploaded to S3 can now carry a **note**, which is stored in DynamoDB and shown again in the file list.

Architecture after week 9:

```
Browser (index.html)
   |-- Login            -> LoginFunction
   |-- Bucket name      -> S3GetBucketNameFunction
   |-- List             -> S3ListFunction     -> S3 ListObjectsV2 + DynamoDB GetItem (per file)
   |-- Upload (+ note)  -> S3UploadFunction   -> S3 PutObject  + DynamoDB PutItem
   '-- Download         -> S3DownloadFunction -> S3 GetObject
```

## 2. What was done

### 2.1 DynamoDB table `FileNotes` (Part 1)
- Region `ap-southeast-1`, partition key `fileKey` (String), on-demand capacity mode.
- The only access pattern: given an S3 object key, find its note.

### 2.2 IAM permissions
Two different identities need two separate policies.

| Policy | Attached to | Permissions | Used for |
|---|---|---|---|
| `cto-dynamodb-table-policy.json` | IAM user `phucnhan` (profile `CTO`) | `CreateTable`, `DescribeTable`, `GetItem`, `PutItem`, `Scan` on `FileNotes` | Creating the table, running CLI commands (Parts 1-2) |
| `cto-lambda-dynamodb-execution-policy.json` | Lambda execution role | `GetItem`, `PutItem` on `FileNotes` | Upload and List Lambdas (Part 3) |

Notes:
- `<ACCOUNT_ID>` was replaced with `353842237220`.
- The `AccessDeniedException` on CLI `put-item` was caused by the user missing the first policy.
- Lambda runs as its execution role, not as the user, so the user's policy has no effect on Lambda.

### 2.3 Lambda code changes
Compared with weeks 7 and 8, only 2 of the 5 functions changed logic.

| Function | Change |
|---|---|
| `S3UploadFunction` | Reads the `X-Note` header and decodes it with `URLDecoder`. After `S3.putObject`, calls `DynamoDB.putItem(fileKey, note)`. Adds `catch DynamoDbException` returning 500 with "File uploaded, but saving its note failed". `Cors.java` allows the extra `x-note` header. `pom.xml` gains the `dynamodb` dependency. |
| `S3ListFunction` | Adds `noteFor(key)` using `GetItem` with a `hasItem()` check. The returned JSON gains a `"note"` field. `pom.xml` gains the `dynamodb` dependency. |
| `LoginFunction` | No logic change. Uses the week 8 version (handles base64-encoded body). |
| `S3DownloadFunction` | No change. Uses the week 7 version. |
| `S3GetBucketNameFunction` | No change. Uses the week 7 version. |

Fixes made inside the `week9` folder:
- The week 9 template had `BUCKET_NAME = "<your-own-bucket>"`. It was changed to `"cloud26-phucnhan-bucket"` in all 4 functions that use the bucket.
- The week 9 template of `LoginFunction` was missing the week 8 fix, so it was copied back from week 8.
- `S3DownloadFunction` and `S3GetBucketNameFunction` were copied from week 7 for consistency.

### 2.4 Frontend `index.html`
- Based on the week 9 template: adds a note input, sends the `X-Note: encodeURIComponent(note)` header on upload, and shows a **Note** column in the table.
- The 5 Function URLs were filled in (taken from the week 8 page).

### 2.5 Build and deploy
- Built with `mvn clean package` (Maven 3.9.12, Java 21) for all 5 functions. The shaded `.jar` is used, not `original-*.jar`.
- `s3-upload-function.jar` and `s3-list-function.jar` were uploaded to `Cloud26S3UploadFunction` and `Cloud26S3ListLambda`. The other three functions did not need redeploying because they match what is already running.

## 3. Testing and results

**Upload with a note**
```
curl.exe -X POST "<UPLOAD_URL>" -H "Authorization: Bearer <token>" -H "X-Filename: db-test.txt" -H "X-Note: a%20test%20note" --data-binary "hello"
```
Result:
```
Uploaded successfully.
Bucket: cloud26-phucnhan-bucket
Key:    db-test.txt
Size:   5 bytes
Note:   a test note
```

**List**
- The first call returned `Internal Server Error`. Cause: the execution role of `Cloud26S3ListLambda` lacked `dynamodb:GetItem`.
- After attaching the policy to the role, list worked:
  - `db-test.txt` has `"note":"a test note"`.
  - Older files (jpg, png) have `"note":""` because they were uploaded before the `FileNotes` table existed.

## 4. Issues encountered

| Issue | Cause | Fix |
|---|---|---|
| `AccessDeniedException` on CLI `put-item` | User `phucnhan` had no DynamoDB permissions | Attached `cto-dynamodb-table-policy.json` to the user |
| `The '<' operator is reserved` in PowerShell | Ran the sample command literally, including `<UPLOAD_URL>` and `<token>` | Replaced them with the real URL and token, quoted |
| `Internal Server Error` on list | List role lacked `GetItem`; the List code only catches `S3Exception`, so a DynamoDB error became a generic 500 | Attached the Lambda policy to the List role |
| Trailing `\` line continuation failed in PowerShell | Bash syntax | Use a backtick instead of `\` |

## 5. Design questions from the lab (summary)

- **Why DynamoDB instead of RDS?** No always-on server, pay-per-request like Lambda, and the access pattern is one key to one item. Downsides: no ad hoc queries such as `WHERE ... LIKE`, and no joins.
- **Why the `{"S": ...}` wrapper on every value?** DynamoDB has no fixed per-column schema, so the type travels with the value.
- **Why `encodeURIComponent` on `X-Note`?** HTTP header values cannot safely carry newlines or non-ASCII characters.
- **Why check `hasItem()`?** A missing item is an empty result, not an error. Older files have no item.
- **N+1:** List makes 1 `ListObjectsV2` call plus N `GetItem` calls. Moving the work into another Lambda does not reduce the call count. `BatchGetItem` (up to 100 items per request) would.
- **Not atomic:** if `putObject` succeeds but `putItem` fails, the file exists in S3 without a note. `TransactWriteItems` cannot help because one of the writes goes to S3. This is acceptable for a cosmetic note; for something like a payment record it would need a repair mechanism.
- **Orphaned notes when an S3 file is deleted:** DynamoDB does not know about S3. The app currently cannot delete files, so this does not happen yet.

## 6. Remaining work

- Part 2: run `put-item` and `get-item` from the CLI after attaching the policy to user `phucnhan`.
- Part 4: test end to end in the browser (log in, upload with a note, check the Note column).
- The week 9 `index.html` currently has no Download button and inserts the note with `innerHTML`. Add the Download button from the week 8 page and switch to `textContent` to avoid HTML injection.
- Stretch exercise (optional): a Delete button that removes both the S3 file and the `FileNotes` item. It needs `s3:DeleteObject` and `dynamodb:DeleteItem`, and the DynamoDB item should be deleted first, then S3.

## 7. Related files

- `database-layer-session-student-lab.md`: the lab handout.
- `cto-dynamodb-table-policy.json`, `cto-lambda-dynamodb-execution-policy.json`: the two IAM policies.
- `s3-serverless-lambdas-db-source/`: source of the 5 Lambda functions.
- `s3-serverless-webapp-db-source/`: `index.html` with URLs filled in.
