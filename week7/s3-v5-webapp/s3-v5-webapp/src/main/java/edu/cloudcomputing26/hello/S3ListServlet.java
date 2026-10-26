package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Lists every object currently in the configured S3 bucket.
 *
 * This is the simplest possible SDK call: no file handling, no request
 * body to parse — just one request, one response, one loop over the
 * result. A good first S3 SDK example before Upload/Download.
 *
 * Requires a valid Authorization header — enforced by AuthFilter, which
 * runs before this servlet for every /s3/* request.
 */
@WebServlet(name = "S3ListServlet", urlPatterns = {"/s3/list"})
public class S3ListServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        S3Client s3 = S3Config.client();

        try (PrintWriter out = response.getWriter()) {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(S3Config.BUCKET_NAME)
                    .build();

            ListObjectsV2Response listResponse = s3.listObjectsV2(listRequest);

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (S3Object object : listResponse.contents()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append("{")
                        .append("\"key\":\"").append(escapeJson(object.key())).append("\",")
                        .append("\"size\":").append(object.size()).append(",")
                        .append("\"lastModified\":\"").append(object.lastModified()).append("\"")
                        .append("}");
            }
            json.append("]");

            out.print(json);

        } catch (S3Exception e) {
            // Common causes for students: bucket name not edited from the
            // placeholder, bucket doesn't exist, wrong region, or the EC2
            // instance role doesn't grant s3:ListBucket on this bucket.
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain;charset=UTF-8");
            try (PrintWriter out = response.getWriter()) {
                out.println("S3 error: " + e.awsErrorDetails().errorMessage());
                out.println("Status code: " + e.statusCode());
            }
        }
    }

    /**
     * Minimal JSON string escaping — just enough for S3 object keys, which
     * can legally contain quotes, backslashes, and other characters that
     * would otherwise break the JSON output.
     */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
