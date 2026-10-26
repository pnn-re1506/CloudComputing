# Lab: Security of vs. in the Cloud

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| S3 | Simple Storage Service (AWS's object storage service) |
| EC2 | Elastic Compute Cloud (AWS's virtual server service) |
| IAM | Identity and Access Management (AWS's user/permissions service) |
| SDK | Software Development Kit |
| HTTP | Hypertext Transfer Protocol |
| JSON | JavaScript Object Notation |
| JS | JavaScript |
| URL | Uniform Resource Locator |
| XSS | Cross-Site Scripting (an attack where malicious JavaScript runs on a page it shouldn't) |
| UI | User Interface |

**You will need:** `s3-v2-webapp` already built, deployed, and working against your own bucket from the S3 SDK Walkthrough lab, plus your EC2 instance running.

By the end of this lab you will have found and demonstrated a real security gap in your own deployed app, fixed it with a per-request token check, and understood why the token is kept in your browser page's own memory instead of `localStorage`.

---

## Part 1 — The AWS Shared Responsibility Model

Two phrases, differing by one preposition:

- **Security *of* the Cloud** — AWS's job. Physical data centers, hardware, the network between AWS regions, S3's durability guarantees, IAM's engine correctly enforcing whatever policy you give it.
- **Security *in* the Cloud** — your job. What you put in a bucket, who you let access it, whether your own application code has a login screen at all.

**Analogy:** your landlord guarantees the building's locks and elevators work — that's security *of* the building. Whether you lock your own apartment door is on you — that's security *in* the building. This is AWS's own real, named model (https://aws.amazon.com/compliance/shared-responsibility-model/), not a course simplification.

**Where IAM fits:** creating your IAM role and scoping its policy to exactly the S3 actions/bucket it needs was security *in* the Cloud work *you* did, using AWS's tools. It's necessary — but as this lab will show, it is not sufficient on its own.

---

## Part 2 — Find the gap in `s3-v2-webapp`

**Try this from a terminal — not a browser, to prove no login or session is involved at all:**

```bash
curl -F "file=@/etc/hostname" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v2-webapp/s3/upload
```

✅ **Checkpoint:** this succeeds — `Uploaded successfully.` — with zero credentials, from a terminal that never opened a browser. Anyone on the internet who knows this URL can do this, as many times as they want.

**Why isn't this an AWS misconfiguration?**
- Your IAM role is correctly scoped — only `GetObject`/`PutObject`/`ListBucket` on this one bucket.
- S3's Block Public Access is on — the bucket isn't directly publicly readable/writable.
- **The gap is entirely in `S3UploadServlet.doPost`**, which runs for *any* incoming request, because nothing in the code ever checks who's calling.

**The key distinction:** the IAM role is the credential that lets *the servlet* talk to S3. It says nothing about who's allowed to talk to *the servlet* — two separate questions, two separate mechanisms, and AWS only hands you the first one automatically.

**Question to consider:** if you deleted the IAM role entirely, would this upload still work? (No — the `S3Client` would have no credentials, and the `PutObject` call itself would fail.) Does having a correct IAM role stop an unauthorized *person* from triggering that upload? (No, not at all.)

---

## Part 3 — Two ways to check a token

The fix: before doing any real work, every servlet should ask "does this request prove the caller is allowed to be here?" `s3-v3-webapp` adds exactly this, using one shared secret token for the whole app (not real user accounts — kept simple on purpose).

### Option A: server-side session (cookie-based)

- A login endpoint checks the token once, then calls `request.getSession(true)` and stores `session.setAttribute("authenticated", true)`.
- Tomcat sends `Set-Cookie: JSESSIONID=...` automatically; the browser resends it on every later request, with no code from you.
- Every other servlet checks `request.getSession(false)`.

```java
// LoginServlet.java — doPost
protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
    String providedToken = request.getParameter("token");
    if (!AuthConfig.TOKEN.equals(providedToken)) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
    }
    HttpSession session = request.getSession(true);
    session.setAttribute("authenticated", true);
    response.setStatus(HttpServletResponse.SC_OK);
}
```

```java
// S3UploadServlet.java — doPost, first lines (same check in every other servlet)
protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session == null || session.getAttribute("authenticated") == null) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
    }
    // ... existing upload logic ...
}
```

**Question to consider:** `LoginServlet` calls `request.getSession(true)`; `S3UploadServlet` calls `request.getSession(false)` — why the difference? (`getSession(true)` means "give me the session, creating one if none exists yet" — right for `LoginServlet`, since a successful login is exactly when you want to start a session. `getSession(false)` means "give me the session only if one already exists, otherwise return `null`" — right for the protected servlets, since you don't want to create a fresh, unauthenticated session just because someone made a request.) Follow-up: what would go wrong if `S3UploadServlet` used `getSession(true)` instead? (The security check still works — a brand-new session has no `authenticated` attribute, so it's still rejected — but every unauthenticated request, including attacker traffic, would now make Tomcat create and track a pointless session object server-side.)

**The precise limitation** (this is worth getting right, not overstating): it's not that sessions "only work with a browser" — a cookie is just an HTTP header, and any client *could* read and resend it. The accurate statement: **a browser does this automatically, for free; almost every other client (`curl`, a script, a mobile app) does not, unless its author adds cookie-jar handling themselves.**

### Option B: per-request token (what you'll build)

- No session. `/login` just checks the token and returns `200`/`401` — nothing is remembered server-side.
- The **client** remembers the token after login and attaches it to **every subsequent request itself**, as an `Authorization: Bearer <token>` header.
- Every servlet independently checks that header.

```java
// AuthConfig.java
static boolean isAuthorized(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
        return false;
    }
    String providedToken = header.substring("Bearer ".length());
    return TOKEN.equals(providedToken);
}
```

**Why this one:** it works identically for a browser page, a `curl` script, or a mobile app — none of them gets anything "for free" the way a browser does with cookies, and none of them is at a disadvantage either.

**Build/deploy `s3-v3-webapp`** (same steps as `s3-v2-webapp`): edit `S3Config.BUCKET_NAME` and `AuthConfig.TOKEN`, `mvn clean package`, deploy the `.war` the same way as before.

**Try it:**
```bash
# No token — blocked:
curl -F "file=@/etc/hostname" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v3-webapp/s3/upload
```
✅ **Checkpoint:** `401`, `Missing or incorrect token.`

```bash
# Correct token — works:
curl -H "Authorization: Bearer <your token>" http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v3-webapp/s3/list
```
✅ **Checkpoint:** `200`, real JSON listing your bucket's contents.

**In the browser:** visit `http://<YOUR_INSTANCE_PUBLIC_IP>:8080/s3-v3-webapp/`. You're shown a login form, not the upload page. Enter the token → the upload/list UI appears.

**Note:** `S3UploadServlet`, `S3ListServlet`, `S3DownloadServlet`, and `S3GetBucketName` each independently call `AuthConfig.isAuthorized(request)` — the check is duplicated across four classes, not centralized. A real application would usually centralize this in a `Filter` (runs before any matching servlet) instead — a good next step, not covered today so the check stays maximally visible in each class it protects.

---

## Part 4 — Where to keep the token on the client

Once logged in, the browser page needs to hold the token somewhere for its remaining interactions. The options:
- `localStorage.setItem(...)` — persists across reloads and even browser restarts.
- `sessionStorage.setItem(...)` — persists across reloads, cleared when the tab closes.
- A plain JS variable (`let token = ...`) — exists only in memory, gone the instant the page reloads or closes.

**`s3-v3-webapp`'s `index.html` uses the plain variable.** Why, precisely:

- This is a **separate decision** from Part 3's session-vs-per-request choice — all three storage options are equally "client remembers the token and resends it." `localStorage` doesn't make the app session-based.
- **The real risk `localStorage` introduces: Cross-Site Scripting (XSS).** `localStorage` is readable by *any* JavaScript running on that page's origin — including malicious JavaScript injected by a successful XSS attack. If an attacker gets even one line of JS to run on this page, `localStorage.getItem("token")` hands them your full, working token — which then sits there, persistently, available to be stolen on *any later page load* for as long as it stays valid.
- An in-memory variable isn't immune to XSS either — a script running *while the page is loaded* can still read it. The real difference is **lifetime and reach**: nothing persists after you reload or navigate away, so there's no standing target for an attack that runs at some unrelated later moment.
- **Try it:** reload `index.html` — you're logged out, and have to log in again. That's the point, not a bug: a short-lived credential is safer than one sitting in persistent storage — the same "shorter-lived is safer" idea as the IAM role's auto-rotated credentials, applied at a completely different layer.

**Question to consider:** does using `localStorage` instead of a plain variable change whether the app is vulnerable to Part 2's original problem (anonymous `curl` uploads)? (No — that's fixed by the token check existing at all, in Part 3, regardless of where the browser later keeps its copy. Two independent fixes to two independent problems.)

---

## Wrap-up

Today you:
1. Named the real distinction between security *of* the Cloud (AWS) and security *in* the Cloud (you) — AWS's own documented model, not a course simplification.
2. Found a real gap in your own deployed app: a correctly-scoped IAM role did **not** stop anonymous uploads, because that's a separate, application-layer responsibility.
3. Fixed it with a per-request token, chosen over a session cookie because it works identically for any kind of client.
4. Kept that token in the page's own memory, not `localStorage`, to limit what a successful XSS attack could steal and for how long.

**Before you leave:** stop your EC2 instance as usual.
