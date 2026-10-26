# S3 Serverless WebApp

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| URL | Uniform Resource Locator |
| JS | JavaScript |
| UI | User Interface |
| CORS | Cross-Origin Resource Sharing |

## What this is

The static front end for the "Going Serverless" session — a single `index.html`, no server-side code, no build step, no Maven project at all. This is the one piece of every earlier version of this app that was *already* "just a file" (every version's `src/main/webapp/index.html` was plain HTML/JS, no JSP, no templating) — what's new is where it's hosted: **AWS Amplify**, not Tomcat on the EC2 instance.

Deploys alongside `labs/s3-serverless-lambdas/` (the five Lambda functions this page calls) — see `lectures/going-serverless-session-instructor-guide.md` for the full session.

## What changed from `s3-v5-webapp`'s `index.html`

- **Five separate URLs instead of five relative paths.** `s3-v5-webapp/index.html` called `login`, `s3/list`, etc. — relative paths, because the page and every endpoint shared one Tomcat origin. This page calls five absolute Function URLs (`LOGIN_URL`, `LIST_URL`, `UPLOAD_URL`, `DOWNLOAD_URL`, `BUCKET_NAME_URL`), each on its own domain, because there is no shared origin anymore.
- **Upload sends raw bytes, not a form.** The upload `<form>` no longer uses `enctype="multipart/form-data"`; the JS reads the chosen file and sends it as the request body directly, with an `X-Filename` header — see `labs/s3-serverless-lambdas/README.md` for why `S3UploadFunction` needs this.
- **Everything else is unchanged on purpose.** The login flow, the in-memory-only `token` variable (never `localStorage`), the bucket-name display, the list table — all identical in behavior to `s3-v5-webapp`. This session is about *where* the code runs, not about changing what the app does.

## Before deploying: fill in the five Function URLs

Open `index.html` and edit:

```js
const LOGIN_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const BUCKET_NAME_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const LIST_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const UPLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
const DOWNLOAD_URL = "https://EDIT-ME.lambda-url.ap-southeast-1.on.aws/";
```

Each value comes from that function's own **Configuration → Function URL** tab in the Lambda console, after creating all five functions in `labs/s3-serverless-lambdas/`.

## Deploy to AWS Amplify (Console)

1. **Amplify console → New app → Deploy without Git provider** (this is a one-file static site — no repo needed for this lab).
2. **App name:** e.g. `s3-serverless-webapp`.
3. **Method:** drag and drop — upload this single `index.html` (Amplify wants a `.zip` or a folder; zipping just this one file is enough).
4. **Save and deploy.**

✅ **Checkpoint:** Amplify gives you a `https://<branch>.<app-id>.amplifyapp.com` URL. Visit it — you should see the same login form every earlier version of this app showed, now served from a domain that has never heard of EC2 or Tomcat.

## Try it end to end

1. Visit your Amplify URL.
2. Log in with your token — this calls `LOGIN_URL`.
3. Confirm the bucket name and file list load — `BUCKET_NAME_URL` and `LIST_URL`.
4. Upload a file — `UPLOAD_URL`. Confirm it appears in the list afterward.

**Question to consider:** open the browser's network tab while doing this. How many different *domains* does one page load now talk to, and what does each one correspond to? (Six: the Amplify domain serving the page itself, plus five separate Function URL domains — one per Lambda function. Compare to every earlier version of this app, which talked to exactly one domain for everything.)

## If something fails silently (blank list, no error shown) — check CORS first

Open the browser console. A CORS failure shows up there, not as an `alert()` from this page's own error handling — the browser blocks the response before any of this page's JS code runs at all. Confirm each Lambda function's response actually includes `Access-Control-Allow-Origin` (see `Cors.java` in `labs/s3-serverless-lambdas/`) before assuming the bug is somewhere else.

## Notes for the instructor

- **Not deployed to a live Amplify app in this repo** — the Function URLs above are placeholders; see the lecture for exact console steps and a live walkthrough.
- **Good moment to contrast with every earlier "stop your EC2 instance" closing line.** This is the first session where that instruction becomes permanent — there is no EC2 instance left for this app to run on at all.
