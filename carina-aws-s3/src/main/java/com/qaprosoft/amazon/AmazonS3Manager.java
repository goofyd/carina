/*******************************************************************************
 * Copyright 2020-2022 Zebrunner Inc (https://www.zebrunner.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.amazon;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.qaprosoft.carina.core.foundation.utils.Configuration;
import com.qaprosoft.carina.core.foundation.utils.Configuration.Parameter;
import com.qaprosoft.carina.core.foundation.utils.common.CommonUtils;
import com.zebrunner.carina.crypto.Algorithm;
import com.zebrunner.carina.crypto.CryptoTool;
import com.zebrunner.carina.crypto.CryptoToolBuilder;
import org.testng.SkipException;

public class AmazonS3Manager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static volatile AmazonS3Manager instance = null;
    private static String CRYPTO_PATTERN = Configuration.get(Parameter.CRYPTO_PATTERN);
    private CryptoTool cryptoTool = null;
    private AmazonS3 s3client = null;

    private AmazonS3Manager() {
    }

    public static synchronized AmazonS3Manager getInstance() {
        if (instance == null) {
            AmazonS3Manager amazonS3Manager = new AmazonS3Manager();

            AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
            String s3region = Configuration.get(Parameter.S3_REGION);
            if (!s3region.isEmpty()) {
                builder.withRegion(Regions.fromName(s3region));
            }

            String accessKey = amazonS3Manager.decryptIfEncrypted(Configuration.get(Parameter.ACCESS_KEY_ID));
            String secretKey = amazonS3Manager.decryptIfEncrypted(Configuration.get(Parameter.SECRET_KEY));
            if (!accessKey.isEmpty() && !secretKey.isEmpty()) {
                BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
                builder.withCredentials(new AWSStaticCredentialsProvider(creds)).build();
            }

            amazonS3Manager.s3client = builder.build();
            instance = amazonS3Manager;
        }
        return instance;
    }

    private String decryptIfEncrypted(String text) {
        Matcher cryptoMatcher = Pattern.compile(CRYPTO_PATTERN)
                .matcher(text);
        String decryptedText = text;
        if (cryptoMatcher.find()) {
            initCryptoTool();
            decryptedText = this.cryptoTool.decrypt(text, CRYPTO_PATTERN);
        }
        return decryptedText;
    }

    private void initCryptoTool() {
        if (this.cryptoTool == null) {
            String cryptoKey = Configuration.get(Parameter.CRYPTO_KEY_VALUE);
            if (cryptoKey.isEmpty()) {
                throw new SkipException("Encrypted data detected, but the crypto key is not found!");
            }
            this.cryptoTool = CryptoToolBuilder.builder()
                    .chooseAlgorithm(Algorithm.find(Configuration.get(Configuration.Parameter.CRYPTO_ALGORITHM)))
                    .setKey(cryptoKey)
                    .build();
        }
    }

    public AmazonS3 getClient() {
        return s3client;
    }

    /**
     * Put any file to Amazon S3 storage.
     * 
     * @param bucket
     *            - S3 bucket name
     * @param key
     *            - S3 storage path. Example:
     *            DEMO/TestSuiteName/TestMethodName/file.txt
     * @param filePath
     *            - local storage path. Example: C:/Temp/file.txt
     * 
     */
    public void put(String bucket, String key, String filePath) {
        put(bucket, key, filePath, null);
    }

    /**
     * Put any file to Amazon S3 storage.
     * 
     * @param bucket
     *            - S3 bucket name
     * @param key
     *            - S3 storage path. Example:
     *            DEMO/TestSuiteName/TestMethodName/file.txt
     * @param filePath
     *            - local storage path. Example: C:/Temp/file.txt
     * @param metadata
     *            - custom tags metadata like name etc
     * 
     */
    public void put(String bucket, String key, String filePath, ObjectMetadata metadata) {

        /*
         * if (mode != S3Mode.WRITE) {
         * if (mode == S3Mode.READ) {
         * LOGGER.warn("Unable to put data in READ mode!");
         * }
         * return;
         * }
         */

        if (key == null) {
            throw new RuntimeException("Key is null!");
        }
        if (key.isEmpty()) {
            throw new RuntimeException("Key is empty!");
        }

        if (filePath == null) {
            throw new RuntimeException("FilePath is null!");
        }
        if (filePath.isEmpty()) {
            throw new RuntimeException("FilePath is empty!");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("File does not exist! " + filePath);
        }

        try {
            LOGGER.debug("Uploading a new object to S3 from a file: "
                    + filePath);

            PutObjectRequest object = new PutObjectRequest(bucket, key, file);
            if (metadata != null) {
                object.setMetadata(metadata);
            }

            s3client.putObject(object);
            LOGGER.debug("Uploaded to S3: '" + filePath + "' with key '" + key
                    + "'");

        } catch (AmazonServiceException ase) {
            LOGGER.error("Caught an AmazonServiceException, which "
                    + "means your request made it "
                    + "to Amazon S3, but was rejected with an error response for some reason.\n"
                    + "Error Message:    " + ase.getMessage() + "\n"
                    + "HTTP Status Code: " + ase.getStatusCode() + "\n"
                    + "AWS Error Code:   " + ase.getErrorCode() + "\n"
                    + "Error Type:       " + ase.getErrorType() + "\n"
                    + "Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            LOGGER.error("Caught an AmazonClientException, which "
                    + "means the client encountered "
                    + "an internal error while trying to "
                    + "communicate with S3, "
                    + "such as not being able to access the network.\n"
                    + "Error Message: " + ace.getMessage());
        }
    }

    /**
     * Get any file from Amazon S3 storage as S3Object.
     * 
     * @param bucket
     *            - S3 Bucket name.
     * @param key
     *            - S3 storage path. Example:
     *            DEMO/TestSuiteName/TestMethodName/file.txt
     * @return S3Object
     */
    public S3Object get(String bucket, String key) {
        // S3Object s3object = null;

        /*
         * if (mode != S3Mode.WRITE && mode != S3Mode.READ) {
         * throw new RuntimeException("Unable to get S3 object in OFF mode!");
         * }
         */

        if (bucket == null) {
            throw new RuntimeException("Bucket is null!");
        }
        if (bucket.isEmpty()) {
            throw new RuntimeException("Bucket is empty!");
        }

        if (key == null) {
            throw new RuntimeException("Key is null!");
        }
        if (key.isEmpty()) {
            throw new RuntimeException("Key is empty!");
        }

        try {
            LOGGER.info("Finding an s3object...");
            // TODO investigate possibility to add percentage of completed
            // downloading
            S3Object s3object = s3client.getObject(new GetObjectRequest(bucket,
                    key));
            LOGGER.info("Content-Type: "
                    + s3object.getObjectMetadata().getContentType());
            return s3object;
            /*
             * GetObjectRequest rangeObjectRequest = new GetObjectRequest(
             * bucketName, key); rangeObjectRequest.setRange(0, 10); S3Object
             * objectPortion = s3client.getObject(rangeObjectRequest);
             */
        } catch (AmazonServiceException ase) {
            LOGGER.error("Caught an AmazonServiceException, which "
                    + "means your request made it "
                    + "to Amazon S3, but was rejected with an error response for some reason.\n"
                    + "Error Message:    " + ase.getMessage() + "\n"
                    + "HTTP Status Code: " + ase.getStatusCode() + "\n"
                    + "AWS Error Code:   " + ase.getErrorCode() + "\n"
                    + "Error Type:       " + ase.getErrorType() + "\n"
                    + "Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            LOGGER.error("Caught an AmazonClientException, which "
                    + "means the client encountered "
                    + "an internal error while trying to "
                    + "communicate with S3, "
                    + "such as not being able to access the network.\n"
                    + "Error Message: " + ace.getMessage());
        }
        // TODO investigate pros and cons returning null
        throw new RuntimeException("Unable to download '" + key
                + "' from Amazon S3 bucket '" + bucket + "'");
    }

    /**
     * Delete file from Amazon S3 storage.
     * 
     * @param bucket
     *            - S3 Bucket name.
     * @param key
     *            - S3 storage path. Example:
     *            DEMO/TestSuiteName/TestMethodName/file.txt
     */
    public void delete(String bucket, String key) {
        if (key == null) {
            throw new RuntimeException("Key is null!");
        }
        if (key.isEmpty()) {
            throw new RuntimeException("Key is empty!");
        }

        try {
            s3client.deleteObject(new DeleteObjectRequest(bucket, key));
        } catch (AmazonServiceException ase) {
            LOGGER.error("Caught an AmazonServiceException, which "
                    + "means your request made it "
                    + "to Amazon S3, but was rejected with an error response for some reason.\n"
                    + "Error Message:    " + ase.getMessage() + "\n"
                    + "HTTP Status Code: " + ase.getStatusCode() + "\n"
                    + "AWS Error Code:   " + ase.getErrorCode() + "\n"
                    + "Error Type:       " + ase.getErrorType() + "\n"
                    + "Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            LOGGER.error("Caught an AmazonClientException.\n"
                    + "Error Message: " + ace.getMessage());
        }
    }

    /**
     * Get latest build artifact from Amazon S3 storage as S3Object.
     * 
     * @param bucket
     *            - S3 Bucket name.
     * @param key
     *            - S3 storage path to your project. Example:
     *            android/MyProject
     * @param pattern
     *            - pattern to find single build artifact Example:
     *            .*prod-google-release.*
     * @return S3ObjectSummary
     */
    public S3ObjectSummary getLatestBuildArtifact(String bucket, String key, Pattern pattern) {
        if (pattern == null) {
            throw new RuntimeException("pattern is null!");
        }

        S3ObjectSummary latestBuild = null;

        ObjectListing objBuilds = s3client.listObjects(bucket, key);

        int i = 0;
        int limit = 100;
        boolean isTruncated = false;
        // by default S3 return only 1000 objects summary so need while cycle here
        do {
            LOGGER.info("looking for s3 artifact using iteration #" + i);

            for (S3ObjectSummary obj : objBuilds.getObjectSummaries()) {
                LOGGER.debug("Existing S3 artifact: " + obj.getKey());
                Matcher matcher = pattern.matcher(obj.getKey());
                if (matcher.find()) {
                    if (latestBuild == null) {
                        latestBuild = obj;
                    }

                    if (obj.getLastModified().after(latestBuild.getLastModified())) {
                        latestBuild = obj;
                    }
                }
            }
            isTruncated = objBuilds.isTruncated();
            objBuilds = s3client.listNextBatchOfObjects(objBuilds);
        } while (isTruncated && ++i < limit);

        if (latestBuild == null) {
            LOGGER.error("Unable to find S3 build artifact by pattern: " + pattern);
        } else {
            LOGGER.info("latest artifact: " + latestBuild.getKey());
        }
        return latestBuild;
    }

    /**
     * Method to download file from s3 to local file system
     * 
     * @param bucketName AWS S3 bucket name
     * @param key (example: android/apkFolder/ApkName.apk)
     * @param file (local file name)
     */
    public void download(final String bucketName, final String key, final File file) {
        download(bucketName, key, file, 10);
    }

    /**
     * Method to download file from s3 to local file system
     * 
     * @param bucketName AWS S3 bucket name
     * @param key (example: android/apkFolder/ApkName.apk)
     * @param file (local file name)
     * @param pollingInterval (polling interval in sec for S3 download status determination)
     */
    public void download(final String bucketName, final String key, final File file, long pollingInterval) {
        LOGGER.info("App will be downloaded from s3.");
        LOGGER.info("[Bucket name: {}] [Key: {}] [File: {}]", bucketName, key, file.getAbsolutePath());

        Download appDownload = TransferManagerBuilder.standard()
                .withS3Client(s3client)
                .build()
                .download(bucketName, key, file);
        try {
            LOGGER.info("Transfer: {}", appDownload.getDescription());
            LOGGER.info("\t State: {}", appDownload.getState());
            LOGGER.info("\t Progress: ");
            // You can poll your transfer's status to check its progress
            while (!appDownload.isDone()) {
                LOGGER.info("\t\t transferred: {}%", (int) (appDownload.getProgress().getPercentTransferred() + 0.5));
                CommonUtils.pause(pollingInterval);
            }
            LOGGER.info("\t State: {}", appDownload.getState());
        } catch (AmazonClientException e) {
            throw new RuntimeException("File wasn't downloaded from s3. See log: ".concat(e.getMessage()));
        }
    }

    /**
     * Method to generate pre-signed object URL to s3 object
     * 
     * @param bucketName AWS S3 bucket name
     * @param key (example: android/apkFolder/ApkName.apk)
     * @param ms espiration time in ms, i.e. 1 hour is 1000*60*60
     * @return url String pre-signed URL
     */
    public URL generatePreSignUrl(final String bucketName, final String key, long ms) {

        java.util.Date expiration = new java.util.Date();
        long msec = expiration.getTime();
        msec += ms;
        expiration.setTime(msec);

        GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucketName, key);
        generatePresignedUrlRequest.setMethod(HttpMethod.GET);
        generatePresignedUrlRequest.setExpiration(expiration);

        URL url = s3client.generatePresignedUrl(generatePresignedUrlRequest);
        return url;
    }

    /*
     * public void read(S3Object s3object) {
     * displayTextInputStream(s3object.getObjectContent()); }
     * 
     * private void displayTextInputStream(InputStream input) { // Read one text
     * line at a time and display. LOGGER.info("File content is: ");
     * BufferedReader reader = new BufferedReader(new InputStreamReader(input));
     * while (true) { String line = null; try { line = reader.readLine(); }
     * catch (IOException e) { LOGGER.error("Failed to read file", e); } if
     * (line == null) break;
     * 
     * System.out.println("    " + line); } }
     */
}
