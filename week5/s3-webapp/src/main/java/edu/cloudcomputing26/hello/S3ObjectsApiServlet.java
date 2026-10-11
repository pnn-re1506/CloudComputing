package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * GET /api/objects — returns the bucket name and every object in it as JSON:
 * {"bucket":"...","objects":[{"key":"...","size":123,"lastModified":"..."}]}
 *
 * The paginator follows continuation tokens, so buckets with more than
 * 1000 objects are listed completely.
 */
@WebServlet(name = "S3ObjectsApiServlet", urlPatterns = {"/api/objects"})
public class S3ObjectsApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");

        try (PrintWriter out = response.getWriter()) {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(S3Config.BUCKET_NAME)
                    .build();

            StringBuilder json = new StringBuilder();
            json.append("{\"bucket\":\"").append(Json.escape(S3Config.BUCKET_NAME))
                .append("\",\"objects\":[");

            boolean first = true;
            for (S3Object object : S3Config.client().listObjectsV2Paginator(listRequest).contents()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"key\":\"").append(Json.escape(object.key()))
                    .append("\",\"size\":").append(object.size())
                    .append(",\"lastModified\":\"").append(object.lastModified())
                    .append("\"}");
            }
            json.append("]}");
            out.print(json);

        } catch (S3Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try (PrintWriter out = response.getWriter()) {
                out.print("{\"error\":\"" + Json.escape(e.awsErrorDetails().errorMessage()) + "\"}");
            }
        }
    }
}
