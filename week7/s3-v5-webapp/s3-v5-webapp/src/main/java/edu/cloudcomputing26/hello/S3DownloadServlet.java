package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Downloads one object from the configured S3 bucket and streams it back
 * to the browser, given its key as a query parameter:
 *
 *   /s3/download?key=example.txt
 *
 * Deliberately does NOT generate a public/pre-signed S3 URL and redirect
 * to it — the object stays private in S3 (default: no public access), and
 * every byte flows through this servlet, authenticated by the EC2
 * instance's own IAM role. This is the more common real-world pattern
 * when you don't want files in the bucket to be directly, permanently
 * reachable by anyone with the URL.
 *
 * Requires a valid Authorization header — enforced by AuthFilter, which
 * runs before this servlet for every /s3/* request.
 */
@WebServlet(name = "S3DownloadServlet", urlPatterns = {"/s3/download"})
public class S3DownloadServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String key = request.getParameter("key");
        if (key == null || key.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().println(
                    "Missing 'key' query parameter. Try: /s3/download?key=<object-key>");
            return;
        }

        S3Client s3 = S3Config.client();

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(S3Config.BUCKET_NAME)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getRequest);

            // Reuse S3's own recorded content type when it has one;
            // otherwise fall back to a generic binary download.
            String contentType = objectBytes.response().contentType();
            response.setContentType(
                    (contentType != null && !contentType.isBlank())
                            ? contentType
                            : "application/octet-stream");
            response.setHeader("Content-Disposition", "inline; filename=\"" + key + "\"");
            response.setContentLengthLong(objectBytes.response().contentLength());

            response.getOutputStream().write(objectBytes.asByteArray());

        } catch (NoSuchKeyException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().println("No object with key \"" + key + "\" in this bucket.");

        } catch (S3Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain;charset=UTF-8");
            try (PrintWriter out = response.getWriter()) {
                out.println("S3 error: " + e.awsErrorDetails().errorMessage());
                out.println("Status code: " + e.statusCode());
            }
        }
    }
}
