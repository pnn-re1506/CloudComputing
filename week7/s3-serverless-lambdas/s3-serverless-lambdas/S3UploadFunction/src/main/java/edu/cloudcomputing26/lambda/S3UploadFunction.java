package edu.cloudcomputing26.lambda;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Replaces s3-v5-webapp's S3UploadServlet — with one real design
 * change, not just a lift-and-shift: the servlet version accepted
 * multipart/form-data (an HTML <form>'s native upload format), parsed
 * for free by Tomcat's getPart(...). A bare Lambda function has no
 * servlet container to do that parsing, and hand-rolling a multipart
 * parser would add real complexity that has nothing to do with Lambda
 * itself. So the static page instead sends the file as the RAW request
 * body (fetch(..., { body: file })) plus the filename in a custom
 * X-Filename header — this function just reads both directly.
 *
 * A Lambda Function URL / API Gateway v2.0 event base64-encodes the
 * body whenever it isn't valid UTF-8 text (any binary file will not
 * be), and sets isBase64Encoded() to true when it did — this function
 * has to check that flag and decode accordingly, something the
 * servlet's getPart() handled invisibly.
 */
public class S3UploadFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

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

        Map<String, String> headers = event.getHeaders();
        String key = (headers == null) ? null : headers.get("x-filename");
        if (key == null || key.isBlank()) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(400)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing X-Filename header.")
                    .build();
        }

        String body = event.getBody();
        if (body == null) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(400)
                    .withHeaders(Cors.HEADERS)
                    .withBody("No file was submitted.")
                    .build();
        }

        byte[] fileBytes = Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? Base64.getDecoder().decode(body)
                : body.getBytes(StandardCharsets.UTF_8);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            S3.putObject(putRequest, RequestBody.fromBytes(fileBytes));

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Uploaded successfully.\n"
                            + "Bucket: " + BUCKET_NAME + "\n"
                            + "Key:    " + key + "\n"
                            + "Size:   " + fileBytes.length + " bytes\n")
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
