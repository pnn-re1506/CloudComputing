package edu.cloudcomputing26.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Replaces s3-v5-webapp's S3ListServlet. Same SDK call as before — this
 * function's only new job, compared to the servlet version, is the
 * AuthCheck call at the top, since there is no AuthFilter running in
 * front of it anymore.
 *
 * Lambda keeps this class "warm" between invocations on the same
 * execution environment, so S3Client is built once in a static
 * initializer (same reasoning as S3Config's shared client in every
 * previous version of this app) rather than once per invocation.
 */
public class S3ListFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    /** EDIT ME: must match the bucket name used by every other function in this app. */
    private static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    private static final S3Client S3 = S3Client.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (Cors.isPreflight(event)) {
            return Cors.preflightResponse();
        }

        if (!AuthCheck.isAuthorized(event.getHeaders())) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing or incorrect token.")
                    .build();
        }

        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .build();

            ListObjectsV2Response listResponse = S3.listObjectsV2(listRequest);

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

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Cors.headersWith("content-type", "application/json;charset=UTF-8"))
                    .withBody(json.toString())
                    .build();

        } catch (S3Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("S3 error: " + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
