# Week 7 Summary: Going Serverless

Region: `ap-southeast-1`. Lab: `going-serverless-session-student-lab.md`.

## Goal

Move the whole S3 web app off Tomcat/EC2. Each servlet becomes its own Lambda
function with its own Function URL, the page moves to AWS Amplify, and the EC2
instance is stopped for good.

| Old servlet | Lambda function |
|---|---|
| `LoginServlet` | `LoginFunction` |
| `S3ListServlet` | `S3ListFunction` |
| `S3UploadServlet` | `S3UploadFunction` |
| `S3DownloadServlet` | `S3DownloadFunction` |
| `S3GetBucketName` | `S3GetBucketNameFunction` |

Every function first calls the `CheckTokenIsValid` Lambda (from week 6) through
`AuthCheck`. The correct password lives only in that Lambda's `TOKEN`
environment variable.

## What was done in the workspace

1. Reviewed the existing code in `week7/s3-serverless-lambdas`. The five
   functions, `AuthCheck`, and `Cors` (including the preflight fix) were already
   complete, so the structure was left unchanged.
2. Set `BUCKET_NAME = "cloud26-phucnhan-bucket"` in `S3ListFunction`,
   `S3UploadFunction`, `S3DownloadFunction`, and `S3GetBucketNameFunction`.
3. Created `week7/s3-serverless-webapp/index.html`:
   - Login flow taken from `s3-v5-webapp` (token kept only in memory).
   - Upload sends the raw file as the body, with the file name in the
     `X-Filename` header.
   - A Download button fetches the file with the token and saves it.
   - Five `*_URL` constants, filled in with the Function URLs you saved.
4. Built every project with `mvn clean package`:
   - `LoginFunction/target/login-function.jar`
   - `S3ListFunction/target/s3-list-function.jar`
   - `S3UploadFunction/target/s3-upload-function.jar`
   - `S3DownloadFunction/target/s3-download-function.jar`
   - `S3GetBucketNameFunction/target/s3-get-bucket-name-function.jar`
   - `week6/check-token-is-valid-lambda/.../target/check-token-is-valid-lambda.jar`

   Upload only these jars, not the `original-*.jar` files.

Nothing was deployed from the workspace. All AWS steps were done by hand in the
console.

## AWS configuration steps

1. **`CheckTokenIsValid`**: Java 21, handler
   `edu.cloudcomputing26.lambda.CheckTokenIsValid::handleRequest`, environment
   variable `TOKEN` set to the password. Test event `"claude26"` should return
   `true`.
2. **Five functions**: Java 21, upload the jar, set the handler to
   `edu.cloudcomputing26.lambda.<ClassName>::handleRequest`, create a Function
   URL with Auth type NONE. Raise Timeout to 15-30 s and Memory to 512 MB.
3. **IAM inline policy** on each function's execution role:
   - `LoginFunction` and `S3GetBucketNameFunction`: `cto-lambda-invoke-policy.json`
   - List, Upload, Download: `cto-lambda-functions-execution-policy.json`
   - Replace `<ACCOUNT_ID>` (from `aws sts get-caller-identity --profile CTO`)
     and `<YOUR_BUCKET_NAME>` with `cloud26-phucnhan-bucket`.
   - The policy name is free text, for example `InvokeCheckTokenIsValid`.
4. **Amplify**: Deploy without Git provider, upload `index.html`.
5. **Stop EC2**: `aws ec2 stop-instances --instance-ids <id> --profile CTO`.

## Testing with curl

Use `curl.exe` in PowerShell on the laptop (plain `curl` is an alias for
`Invoke-WebRequest`).

```powershell
curl.exe -i -X POST "<LOGIN_URL>" -H "Content-Type: text/plain" -d "claude26"
curl.exe "<GETBUCKETNAME_URL>" -H "Authorization: Bearer claude26"
curl.exe "<LIST_URL>" -H "Authorization: Bearer claude26"
curl.exe -X POST "<UPLOAD_URL>" -H "Authorization: Bearer claude26" -H "X-Filename: test.txt" --data-binary "hello"
curl.exe "<DOWNLOAD_URL>?key=test.txt" -H "Authorization: Bearer claude26"
```

## Open issue

`LoginFunction` returned `401 Missing or incorrect token.` for the correct
password. The function itself ran (CORS headers were present), and `TOKEN` in
the console shows `claude26`. Next checks:

1. Test `CheckTokenIsValid` directly in the console with the event `"claude26"`.
   - `true`: the function is fine, look at how the body reaches `LoginFunction`.
   - `false`: retype the `TOKEN` value by hand (hidden space or case difference).
   - Error: check handler, jar, runtime, and timeout.
2. Repeat the login curl with `-H "Content-Type: text/plain"`. The browser sends
   text, while `curl -d` defaults to `application/x-www-form-urlencoded`.
3. Call `S3GetBucketNameFunction` with the `Authorization` header to see whether
   the problem is in `LoginFunction` or in `AuthCheck`.

## Trade-offs (from the lab)

- Gain: nothing billed while idle, no server to patch, no instance to remember
  to stop.
- Cost: CORS everywhere, a custom upload format, `AuthCheck` duplicated in five
  functions, five things to deploy instead of one `.war`, and harder debugging
  across six domains.
- A Function URL response is capped at 6 MB, which limits download size.

## Notes

- Week 6 work was skipped as requested. The earlier login web app experiment in
  `week6/s3-login-webapp` was reverted and no longer exists.
- Keep `cloud26-key.pem` and the access key CSV out of OneDrive.
