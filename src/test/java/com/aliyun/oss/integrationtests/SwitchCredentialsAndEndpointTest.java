/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.oss.integrationtests;

import static com.aliyun.oss.integrationtests.TestConfig.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Ignore;
import org.junit.Test;

import com.aliyun.oss.ClientErrorCode;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;

public class SwitchCredentialsAndEndpointTest extends TestBase {

    /* Indicate whether credentials switching starts prior to credentials verification */
    private volatile boolean switchStarted = false;
    
    private static final int loopTimes = 100;
    private static final int switchInterval = 50; // unit in milliseconds

    @Test
    public void testSwitchInvalidCredentialsAndEndpoint() {
        CredentialsProvider credsProvider = client.getCredentialsProvider();
        Credentials defaultCreds = credsProvider.getCredentials();
        assertEquals(OSS_TEST_ACCESS_KEY_ID, defaultCreds.getAccessKeyId());
        assertEquals(OSS_TEST_ACCESS_KEY_SECRET, defaultCreds.getSecretAccessKey());
        
        // Switch to invalid credentials
        Credentials invalidCreds = new DefaultCredentials("invalid-access-id", "invalid-access-key");
        client.switchCredentials(invalidCreds);
        
        // Verify invalid credentials under default endpoint
        try {
            client.getBucketLocation(bucketName);
            fail("Should not be able to get bucket location with invalid credentials.");
        } catch (OSSException ex) {
            assertEquals(OSSErrorCode.INVALID_ACCESS_KEY_ID, ex.getErrorCode());
        }
        
        // Switch to valid endpoint
        client.setEndpoint("invalid-endpoint");
        
        // Verify second credentials under invalid endpoint
        try {
            client.getBucketLocation(bucketName);
            fail("Should not be able to get bucket location with second credentials.");
        } catch (ClientException ex) {
            assertEquals(ClientErrorCode.UNKNOWN_HOST, ex.getErrorCode());
        } finally {
            restoreClient();
        }
    }
    
    @Test
    public void testSwitchCredentialsSynchronously() throws Exception {
        /* Ensure credentials switching prior to credentials verification at first time */
        final Object ensureSwitchFirst = new Object();
        final Object verifySynchronizer = new Object();
        final Object switchSynchronizer = new Object();
        
        // Verify whether credentials switching work as expected
        Thread verifyThread = new Thread(new Runnable() {
            
            @Override
            public void run() {                
                synchronized (ensureSwitchFirst) {
                    if (!switchStarted) {
                        try {
                            ensureSwitchFirst.wait();
                        } catch (InterruptedException e) { }
                    }
                }
                
                int l = 0;
                do {
                    // Wait for credentials switching completion
                    synchronized (verifySynchronizer) {
                        try {
                            verifySynchronizer.wait();
                        } catch (InterruptedException e) { }
                    }
                    
                    CredentialsProvider credsProvider = client.getCredentialsProvider();
                    Credentials currentCreds = credsProvider.getCredentials();
                    
                    try {
                        String loc = client.getBucketLocation(bucketName);
                        assertEquals(OSS_TEST_REGION, loc);
                        assertEquals(OSS_TEST_ACCESS_KEY_ID, currentCreds.getAccessKeyId());
                        assertEquals(OSS_TEST_ACCESS_KEY_SECRET, currentCreds.getSecretAccessKey());
                    } catch (OSSException ex) {
                        assertEquals(OSSErrorCode.INVALID_ACCESS_KEY_ID, ex.getErrorCode());
                        assertEquals(OSS_TEST_ACCESS_KEY_ID, currentCreds.getAccessKeyId());
                        assertEquals(OSS_TEST_ACCESS_KEY_SECRET, currentCreds.getSecretAccessKey());
                    }
                    
                    // Notify credentials switching
                    synchronized (switchSynchronizer) {
                        switchSynchronizer.notify();
                    }
                    
                } while (++l < loopTimes);
            }
        });
        
        // Switch credentials(including valid and invalid ones) synchronously
        Thread switchThread = new Thread(new Runnable() {
            
            @Override
            public void run() {        
                int l = 0;
                boolean firstSwitch = false;
                do {
                    Credentials secondCreds =
                            new DefaultCredentials(OSS_TEST_ACCESS_KEY_ID, OSS_TEST_ACCESS_KEY_SECRET);
                    client.switchCredentials(secondCreds);
                    CredentialsProvider credsProvider = client.getCredentialsProvider();
                    secondCreds = credsProvider.getCredentials();
                    assertEquals(OSS_TEST_ACCESS_KEY_ID, secondCreds.getAccessKeyId());
                    assertEquals(OSS_TEST_ACCESS_KEY_SECRET, secondCreds.getSecretAccessKey());

                    if (!firstSwitch) {
                        synchronized (ensureSwitchFirst) {
                            switchStarted = true;
                            ensureSwitchFirst.notify();
                        }
                        firstSwitch = true;
                    }
                    
                    try {
                        Thread.sleep(switchInterval);
                    } catch (InterruptedException e) { }
                    
                    /* 
                     * Notify credentials verification and wait for next credentials switching.
                     * TODO: The two synchronized clauses below should be combined as atomic operation.
                     */
                    synchronized (verifySynchronizer) {
                        verifySynchronizer.notify();
                    }
                    synchronized (switchSynchronizer) {
                        try {
                            switchSynchronizer.wait();
                        } catch (InterruptedException e) {}
                    }
                } while (++l < loopTimes);
            }
        });
        
        verifyThread.start();
        switchThread.start();
        verifyThread.join();
        switchThread.join();
        
        restoreClient();
    }
    
    @Test
    public void testSwitchEndpointSynchronously() throws Exception {
        /* Ensure endpoint switching prior to endpoint verification at first time */
        final Object ensureSwitchFirst = new Object();
        final Object verifySynchronizer = new Object();
        final Object switchSynchronizer = new Object();
        
        // Verify whether endpoint switching work as expected
        Thread verifyThread = new Thread(new Runnable() {
            
            @Override
            public void run() {                
                synchronized (ensureSwitchFirst) {
                    if (!switchStarted) {
                        try {
                            ensureSwitchFirst.wait();
                        } catch (InterruptedException e) { }
                    }
                }
                
                int l = 0;
                do {
                    // Wait for endpoint switching completion
                    synchronized (verifySynchronizer) {
                        try {
                            verifySynchronizer.wait();
                        } catch (InterruptedException e) { }
                    }
                    
                    CredentialsProvider credsProvider = client.getCredentialsProvider();
                    Credentials currentCreds = credsProvider.getCredentials();
                    
                    String loc = client.getBucketLocation(bucketName);
                    assertEquals(OSS_TEST_REGION, loc);
                    assertEquals(OSS_TEST_ACCESS_KEY_ID, currentCreds.getAccessKeyId());
                    assertEquals(OSS_TEST_ACCESS_KEY_SECRET, currentCreds.getSecretAccessKey());
                    
                    /*
                     * Since the default OSSClient is the same as the second OSSClient, let's
                     * do a simple verification. 
                     */
                    String secondLoc = client.getBucketLocation(bucketName);
                    assertEquals(loc, secondLoc);
                    assertEquals(OSS_TEST_REGION, secondLoc);
                    CredentialsProvider secondCredsProvider = client.getCredentialsProvider();
                    Credentials secondCreds = secondCredsProvider.getCredentials();
                    assertEquals(OSS_TEST_ACCESS_KEY_ID, secondCreds.getAccessKeyId());
                    assertEquals(OSS_TEST_ACCESS_KEY_SECRET, secondCreds.getSecretAccessKey());
                    
                    // Notify endpoint switching
                    synchronized (switchSynchronizer) {
                        restoreClient();
                        switchSynchronizer.notify();
                    }
                    
                } while (++l < loopTimes);
            }
        });
        
        // Switch endpoint synchronously
        Thread switchThread = new Thread(new Runnable() {
            
            @Override
            public void run() {        
                int l = 0;
                boolean firstSwitch = false;
                do {
                    /* 
                     * Switch both credentials and endpoint, now the default OSSClient is the same as 
                     * the second OSSClient actually.
                     */
                    Credentials secondCreds =
                            new DefaultCredentials(OSS_TEST_ACCESS_KEY_ID, OSS_TEST_ACCESS_KEY_SECRET);
                    client.switchCredentials(secondCreds);
                    client.setEndpoint(OSS_TEST_ENDPOINT);

                    if (!firstSwitch) {
                        synchronized (ensureSwitchFirst) {
                            switchStarted = true;
                            ensureSwitchFirst.notify();
                        }
                        firstSwitch = true;
                    }
                    
                    try {
                        Thread.sleep(switchInterval);
                    } catch (InterruptedException e) { }
                    
                    /* 
                     * Notify credentials verification and wait for next credentials switching.
                     * TODO: The two synchronized clauses below should be combined as atomic operation.
                     */
                    synchronized (verifySynchronizer) {
                        verifySynchronizer.notify();
                    }
                    synchronized (switchSynchronizer) {
                        try {
                            switchSynchronizer.wait();
                        } catch (InterruptedException e) {}
                    }
                } while (++l < loopTimes);
            }
        });
        
        verifyThread.start();
        switchThread.start();
        verifyThread.join();
        switchThread.join();
        
        restoreClient();
    }
    
}
