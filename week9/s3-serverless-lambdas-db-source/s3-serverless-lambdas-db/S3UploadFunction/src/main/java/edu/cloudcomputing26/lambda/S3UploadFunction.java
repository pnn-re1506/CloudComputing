package edu.cloudcomputing26.lambda;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import java.net.URLDecoder;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
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
 *
 * New in this lab: the note the user types alongside the upload arrives
 * in its own X-Note header, URL-encoded by the page before sending —
 * HTTP header values can't safely carry newlines or arbitrary Unicode,
 * the same reason query strings get URL-encoded. After the S3 write
 * succeeds, this function writes the note to DynamoDB as its own PutItem
 * call, keyed by the same object key used in S3. The two writes are not
 * transactional: if the DynamoDB call fails after the S3 upload already
 * succeeded, the file exists with no note yet, something worth naming
 * out loud rather than quietly glossing over (see the lecture for the
 * discussion of why this app doesn't try to fix that here).
 */
public class S3UploadFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    /** EDIT ME: must match the bucket name used by every other function in this app. */
    private static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    /** EDIT ME: must match the DynamoDB table name created for this lab. */
    private static final String TABLE_NAME = "FileNotes";

    private static final S3Client S3 = S3Client.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.builder()
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

        String encodedNote = headers.get("x-note");
        String note = (encodedNote == null) ? "" : URLDecoder.decode(encodedNote, StandardCharsets.UTF_8);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            S3.putObject(putRequest, RequestBody.fromBytes(fileBytes));

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(Map.of(
                            "fileKey", AttributeValue.fromS(key),
                            "note", AttributeValue.fromS(note)))
                    .build();

            DYNAMO_DB.putItem(putItemRequest);

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Uploaded successfully.\n"
                            + "Bucket: " + BUCKET_NAME + "\n"
                            + "Key:    " + key + "\n"
                            + "Size:   " + fileBytes.length + " bytes\n"
                            + "Note:   " + note + "\n")
                    .build();

        } catch (S3Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("S3 error: " + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();

        } catch (DynamoDbException e) {
            // The file itself is already in S3 at this point — only the
            // note failed to save. Say so plainly rather than returning
            // a generic failure that implies the whole upload was lost.
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("File uploaded, but saving its note failed: "
                            + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();
        }
    }
}
