package edu.cloudcomputing26.lambda;

import java.util.Base64;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Replaces s3-v5-webapp's S3DownloadServlet:
 *
 *   GET <function URL>?key=example.txt
 *
 * Same "stream every byte through the function, never a public/
 * pre-signed S3 URL" design as the servlet version. New here: the
 * response body is base64-encoded with isBase64Encoded(true) set,
 * since API Gateway v2.0 payloads are JSON and can't carry raw binary
 * directly — the browser's fetch() still gets the real bytes back,
 * decoding happens for you before your JS ever sees the response.
 *
 * Worth flagging in class: a Lambda Function URL's synchronous
 * response is capped at 6 MB. The old servlet had no such limit (it
 * streamed straight from S3 to the HTTP response). For this lab's
 * small files it's invisible, but it's a real constraint of this
 * architecture that the old one didn't have.
 */
public class S3DownloadFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

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

        Map<String, String> queryParams = event.getQueryStringParameters();
        String key = (queryParams == null) ? null : queryParams.get("key");
        if (key == null || key.isBlank()) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(400)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing 'key' query parameter. Try: ?key=<object-key>")
                    .build();
        }

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> objectBytes = S3.getObjectAsBytes(getRequest);

            String contentType = objectBytes.response().contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "content-type", contentType,
                            "content-disposition", "inline; filename=\"" + key + "\""))
                    .withIsBase64Encoded(true)
                    .withBody(Base64.getEncoder().encodeToString(objectBytes.asByteArray()))
                    .build();

        } catch (NoSuchKeyException e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(404)
                    .withHeaders(Cors.HEADERS)
                    .withBody("No object with key \"" + key + "\" in this bucket.")
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
}
