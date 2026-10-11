# Cloud Computing

Hands-on labs and an assignment for the Cloud Computing course, built on AWS (region `ap-southeast-1`, Singapore).

## What this repo covers

| Week | Topic | Folder |
|---|---|---|
| 3 | Launch an EC2 server, connect over SSH, use the AWS CLI, install MySQL and Tomcat 10 | `week3/` |
| 4 | Build Java web apps with Maven, deploy to Tomcat, use S3 and IAM roles | `week4/` |
| 5 | Assignment: Java web app that lists and uploads S3 objects | `week5/` |

## Tech stack

- AWS: EC2, S3, IAM
- Ubuntu 26.04, MySQL, Apache Tomcat 10.1
- Java 21+, Maven, Jakarta Servlet 6.0
- AWS SDK for Java v2

## Project structure

```
CloudComputing/
├── week3/                      # EC2 lab and optional Tomcat Manager / MySQL Workbench guides
├── week4/
│   ├── hello-world-webapp/     # Hello World servlet
│   └── s3-webapp/              # list / upload / download servlets
├── week5/
│   ├── s3-sdk-walkthrough-session-student-lab.md
│   └── s3-webapp/              # assignment solution
├── SESSION_SUMMARY.md          # what was done, week by week
└── README.md
```

## Week 5 assignment

A single page that:

1. Shows the configured S3 bucket name on load.
2. Shows a table of every object (key, size, last modified) on load.
3. Lets the user pick a local file and upload it.
4. Refreshes the table after a successful upload, without reloading the page.

## Build and deploy

Prerequisites: JDK 21+, Maven, a running EC2 instance with Tomcat, an S3 bucket, and an IAM role attached to the instance.

1. Set your bucket name in `S3Config.java` (`BUCKET_NAME`).
2. Build:
   ```powershell
   cd week5\s3-webapp
   mvn clean package
   ```
3. Copy the WAR to the server and deploy:
   ```powershell
   scp -i <key.pem> target\s3-webapp.war ubuntu@<IP>:/tmp/
   ssh -i <key.pem> ubuntu@<IP>
   ```
   ```bash
   sudo cp /tmp/s3-webapp.war /opt/tomcat/webapps/
   sudo chown tomcat: /opt/tomcat/webapps/s3-webapp.war
   ```
4. Open `http://<IP>:8080/s3-webapp/`.

The instance's public IP changes after every stop/start, so look it up again each time.

## Security

- Never commit `*.pem` keys or access key CSV files. They are listed in `.gitignore`.
- The code contains no AWS credentials; the SDK uses the IAM role attached to the EC2 instance.
- Restrict SSH and the Tomcat Manager app to your own IP.

## Housekeeping

- Stop the EC2 instance when you finish (do not terminate it).
- Do not delete the S3 bucket; later sessions reuse it.
