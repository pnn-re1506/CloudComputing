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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * POST /api/upload — receives a multipart form field named "file" (sent by
 * fetch() from the page) and stores it in the bucket. Returns JSON; the page
 * then re-fetches /api/objects to refresh the table without a page reload.
 */
@WebServlet(name = "S3UploadApiServlet", urlPatterns = {"/api/upload"})
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024,
        maxFileSize = 1024 * 1024 * 25,
        maxRequestSize = 1024 * 1024 * 30
)
public class S3UploadApiServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");

        Part filePart = request.getPart("file");
        if (filePart == null || filePart.getSize() == 0
                || filePart.getSubmittedFileName() == null
                || filePart.getSubmittedFileName().isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("{\"error\":\"No file was submitted.\"}");
            return;
        }

        String key = filePart.getSubmittedFileName();

        try (InputStream in = filePart.getInputStream();
             PrintWriter out = response.getWriter()) {

            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(S3Config.BUCKET_NAME)
                    .key(key)
                    .build();
            S3Config.client().putObject(put, RequestBody.fromInputStream(in, filePart.getSize()));

            out.print("{\"ok\":true,\"key\":\"" + Json.escape(key) + "\"}");

        } catch (S3Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().print(
                    "{\"error\":\"" + Json.escape(e.awsErrorDetails().errorMessage()) + "\"}");
        }
    }
}
