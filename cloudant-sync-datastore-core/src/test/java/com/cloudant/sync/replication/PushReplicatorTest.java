/**
 * Copyright (c) 2013 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.replication;

import com.cloudant.common.RequireRunningCouchDB;
import com.cloudant.common.TestOptions;
import com.cloudant.http.CookieInterceptor;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(RequireRunningCouchDB.class)
public class PushReplicatorTest extends ReplicationTestBase {

    BasicReplicator replicator;

    @Before
    public void setUp() throws Exception {

        super.setUp();

        PushReplication push = this.createPushReplication();
        replicator = (BasicReplicator) ReplicatorFactory.oneway(push);
    }

    private void prepareTwoDocumentsInLocalDB() throws Exception {
        Bar bar1 = BarUtils.createBar(datastore, "Tom", 31);
        Bar bar2 = BarUtils.createBar(datastore, "Jerry", 52);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void start_StartedThenComplete() throws Exception {
        prepareTwoDocumentsInLocalDB();

        TestReplicationListener listener = new TestReplicationListener();
        Assert.assertEquals(Replicator.State.PENDING, replicator.getState());
        replicator.getEventBus().register(listener);
        replicator.start();
        Assert.assertEquals(Replicator.State.STARTED, replicator.getState());

        while(replicator.getState() != Replicator.State.COMPLETE) {
            Thread.sleep(1000);
        }

        Assert.assertEquals(Replicator.State.COMPLETE, replicator.getState());
        Assert.assertEquals(2,  remoteDb.changes("0", 100).size());

        Assert.assertTrue(listener.finishCalled);
        Assert.assertFalse(listener.errorCalled);
    }

    @Test
    public void start_StartedThenStopped() throws Exception {

        int count = 5000;
        for (int i = 0; i < count; i++) {
            BarUtils.createBar(datastore, "docnum", i);
        }

        TestReplicationListener listener = new TestReplicationListener();
        Assert.assertEquals(Replicator.State.PENDING, replicator.getState());
        replicator.getEventBus().register(listener);
        replicator.start();
        Assert.assertEquals(Replicator.State.STARTED, replicator.getState());
        Thread.sleep(1000); //just to make sure a few docs are pushed
        replicator.stop();

        //force wait for the replicator to finish stopping before making tests.
        int maxTries = 1000*60; //60 seconds is the longest we'll wait
        int haveBeenWaiting = 0;
        while (!listener.finishCalled && !listener.errorCalled) {
            Thread.sleep(1000);
            if (haveBeenWaiting >= maxTries) {
                Assert.fail("replicator did not stop after waiting 60 seconds.");
                break;
            }
            haveBeenWaiting += 1000;
        }

        Assert.assertTrue(listener.finishCalled);
        Assert.assertFalse(listener.errorCalled);

        if (count != remoteDb.changes("0", 10000).size()) {
            Assert.assertEquals(Replicator.State.STOPPED, replicator.getState());
        }
        else {
            Assert.assertEquals(Replicator.State.COMPLETE, replicator.getState());
            Assert.fail("replicator did not stop before all docs were pushed");
        }

    }

    @Test
    public void testRequestInterceptors() throws Exception {

        //to test the interceptors we count if the interceptor gets called

        InterceptorCallCounter interceptorCallCounter = new InterceptorCallCounter();
        PushReplication pushReplication = createPushReplication();
        pushReplication.requestInterceptors.add(interceptorCallCounter);
        runReplicationUntilComplete(pushReplication);
        Assert.assertTrue(interceptorCallCounter.interceptorRequestTimesCalled >= 1);

    }

    @Test
    public void testResponseInterceptors() throws Exception {

        InterceptorCallCounter interceptorCallCounter = new InterceptorCallCounter();
        PushReplication pushReplication = createPushReplication();
        pushReplication.responseInterceptors.add(interceptorCallCounter);
        runReplicationUntilComplete(pushReplication);
        Assert.assertTrue(interceptorCallCounter.interceptorResponseTimesCalled >= 1);
    }

    @Test
    public void testRequestInterceptorsThroughBuilder() throws Exception {
        InterceptorCallCounter interceptorCallCounter = new InterceptorCallCounter();

        TestReplicationListener listener = new TestReplicationListener();
        ReplicatorBuilder replicatorBuilder = ReplicatorBuilder.push()
                .from(this.datastore)
                .to(this.remoteDb.couchClient.getRootUri())
                .addRequestInterceptors(interceptorCallCounter)
                .addResponseInterceptors(interceptorCallCounter);
        if (TestOptions.COOKIE_AUTH) {
            CookieInterceptor ci = new CookieInterceptor(TestOptions.COUCH_USERNAME, TestOptions.COUCH_PASSWORD);
            replicatorBuilder.addRequestInterceptors(ci);
            replicatorBuilder.addResponseInterceptors(ci);
        }
        Replicator replicator = replicatorBuilder.build();

        replicator.getEventBus().register(listener);
        replicator.start();

        while(replicator.getState() != Replicator.State.COMPLETE && replicator.getState() != Replicator.State.ERROR) {
            Thread.sleep(50);
        }

        Assert.assertEquals(Replicator.State.COMPLETE, replicator.getState());
        Assert.assertFalse(listener.errorCalled);
        Assert.assertTrue(listener.finishCalled);

        //check that the response and request interceptors have been called.
        Assert.assertTrue(interceptorCallCounter.interceptorResponseTimesCalled >= 1);
        Assert.assertTrue(interceptorCallCounter.interceptorRequestTimesCalled >= 1);

    }
}
