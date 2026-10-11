package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Uploads a file from an HTML form (multipart/form-data) into the
 * configured S3 bucket, using the object key the user provides.
 *
 * GET  /s3/upload — shows a small HTML upload form.
 * POST /s3/upload — receives the form submission and calls S3 PutObject.
 *
 * @MultipartConfig is required on the class for request.getPart(...) to
 * work at all — without it, Tomcat won't parse the request body as
 * multipart/form-data and getPart() throws.
 */
@WebServlet(name = "S3UploadServlet", urlPatterns = {"/s3/upload"})
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024,      // 1 MB: buffer smaller files in memory
        maxFileSize = 1024 * 1024 * 25,       // 25 MB: reject anything larger
        maxRequestSize = 1024 * 1024 * 30     // 30 MB: overall request size cap
)
public class S3UploadServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");
        try (PrintWriter out = response.getWriter()) {
            out.println("<!DOCTYPE html><html><head><title>Upload to S3</title></head><body>");
            out.println("<h1>Upload a file to S3</h1>");
            out.println("<p>Bucket: <code>" + S3Config.BUCKET_NAME + "</code></p>");
            out.println("<form method=\"post\" enctype=\"multipart/form-data\">");
            out.println("  <input type=\"file\" name=\"file\" required>");
            out.println("  <button type=\"submit\">Upload</button>");
            out.println("</form>");
            out.println("<p><a href=\"list\">View bucket contents</a></p>");
            out.println("</body></html>");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/plain;charset=UTF-8");

        Part filePart = request.getPart("file");
        if (filePart == null || filePart.getSize() == 0) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("No file was submitted.");
            return;
        }

        // getSubmittedFileName() is the original filename from the
        // student's own computer — we use it directly as the S3 object
        // key here for simplicity. (In a real application you'd usually
        // sanitize this or generate your own key instead of trusting
        // client-supplied input as-is.)
        String key = filePart.getSubmittedFileName();
        S3Client s3 = S3Config.client();

        try (InputStream fileContent = filePart.getInputStream()) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(S3Config.BUCKET_NAME)
                    .key(key)
                    .build();

            s3.putObject(putRequest, RequestBody.fromInputStream(fileContent, filePart.getSize()));

            try (PrintWriter out = response.getWriter()) {
                out.println("Uploaded successfully.");
                out.println("Bucket: " + S3Config.BUCKET_NAME);
                out.println("Key:    " + key);
                out.println("Size:   " + filePart.getSize() + " bytes");
            }

        } catch (S3Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try (PrintWriter out = response.getWriter()) {
                out.println("S3 error: " + e.awsErrorDetails().errorMessage());
                out.println("Status code: " + e.statusCode());
            }
        }
    }
}
