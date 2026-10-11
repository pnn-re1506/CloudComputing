package edu.cloudcomputing26.hello;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Shared S3 configuration for the S3-backed servlets.
 *
 * EDIT BUCKET_NAME below to your own bucket before building — S3 bucket
 * names are globally unique across all of AWS, so the placeholder value
 * will not work as-is.
 *
 * No AWS access key or secret key appears anywhere in this project. The
 * S3Client below is built with no explicit credentials, which means the
 * SDK's default credential provider chain is used. On this EC2 instance,
 * that chain finds the IAM role attached to the instance itself (via the
 * instance metadata service) and uses temporary, automatically-rotated
 * credentials from that role — this is the AWS-recommended pattern, and
 * it's why no student ever has to hardcode a secret key in this code.
 */
final class S3Config {

    /** EDIT ME: replace with your own globally-unique bucket name. */
    static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    /** Must match the region the bucket was created in (this course: ap-southeast-1). */
    static final Region REGION = Region.AP_SOUTHEAST_1;

    private static final S3Client CLIENT = S3Client.builder()
            .region(REGION)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    static S3Client client() {
        return CLIENT;
    }

    private S3Config() {
        // no instances
    }
}
