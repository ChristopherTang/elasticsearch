/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.percolator;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.percolate.PercolateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.test.integration.percolator.SimplePercolatorTests.convertFromTextArray;
import static org.hamcrest.Matchers.*;

/**
 *
 */
public class ConcurrentPercolatorTests extends AbstractNodesTests {


    @Override
    public void beforeClass() throws Exception {
        Settings settings = settingsBuilder()
                .put("cluster.name", "percolator-test-cluster-" + NetworkUtils.getLocalAddress().getHostName() + "_" + System.currentTimeMillis())
                .put("gateway.type", "none").build();
        logger.info("--> starting 3 nodes");
        startNode("node1", settings);
        startNode("node2", settings);
        startNode("node3", settingsBuilder().put(settings).put("node.client", true));
    }


    @Before
    public void beforeTest() throws Exception {
        setUp();
        client().admin().indices().prepareDelete().execute().actionGet();
        ensureGreen();
    }

    @Test
    public void testSimpleConcurrentPerculator() throws Exception {
        client().admin().indices().prepareCreate("index").setSettings(
                ImmutableSettings.settingsBuilder()
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 0)
                        .build()
        ).execute().actionGet();
        ensureGreen();

        final XContentBuilder onlyField1 = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field1", 1)
                .endObject().endObject();
        final XContentBuilder onlyField2 = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field2", "value")
                .endObject().endObject();
        final XContentBuilder bothFields = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field1", 1)
                .field("field2", "value")
                .endObject().endObject();


        // We need to index a document / define mapping, otherwise field1 doesn't get reconized as number field.
        // If we don't do this, then 'test2' percolate query gets parsed as a TermQuery and not a RangeQuery.
        // The percolate api doesn't parse the doc if no queries have registered, so it can't lazily create a mapping
        client().prepareIndex("index", "type", "1").setSource(XContentFactory.jsonBuilder().startObject()
                .field("field1", 1)
                .field("field2", "value")
                .endObject()).execute().actionGet();

        client().prepareIndex("index", "_percolator", "test1")
                .setSource(XContentFactory.jsonBuilder().startObject().field("query", termQuery("field2", "value")).endObject())
                .execute().actionGet();
        client().prepareIndex("index", "_percolator", "test2")
                .setSource(XContentFactory.jsonBuilder().startObject().field("query", termQuery("field1", 1)).endObject())
                .execute().actionGet();

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicInteger counts = new AtomicInteger(0);
        final AtomicBoolean assertionFailure = new AtomicBoolean(false);
        Thread[] threads = new Thread[5];

        for (int i = 0; i < threads.length; i++) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        while (!stop.get()) {
                            int count = counts.incrementAndGet();
                            if ((count > 10000)) {
                                stop.set(true);
                            }
                            PercolateResponse percolate;
                            if (count % 3 == 0) {
                                percolate = client().preparePercolate("index", "type").setSource(bothFields)
                                        .execute().actionGet();
                                assertThat(percolate.getMatches(), arrayWithSize(2));
                                assertThat(convertFromTextArray(percolate.getMatches()), arrayContainingInAnyOrder("test1", "test2"));
                            } else if (count % 3 == 1) {
                                percolate = client().preparePercolate("index", "type").setSource(onlyField2)
                                        .execute().actionGet();
                                assertThat(percolate.getMatches(), arrayWithSize(1));
                                assertThat(convertFromTextArray(percolate.getMatches()), arrayContaining("test1"));
                            } else {
                                percolate = client().preparePercolate("index", "type").setSource(onlyField1)
                                        .execute().actionGet();
                                assertThat(percolate.getMatches(), arrayWithSize(1));
                                assertThat(convertFromTextArray(percolate.getMatches()), arrayContaining("test2"));
                            }
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (AssertionError e) {
                        assertionFailure.set(true);
                        Thread.currentThread().interrupt();
                    }
                }
            };
            threads[i] = new Thread(r);
            threads[i].start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(assertionFailure.get(), equalTo(false));
    }

    @Test
    public void testConcurrentAddingAndPercolating() throws Exception {
        client().admin().indices().prepareCreate("index").setSettings(
                ImmutableSettings.settingsBuilder()
                        .put("index.number_of_shards", 2)
                        .put("index.number_of_replicas", 1)
                        .build()
        ).execute().actionGet();
        ensureGreen();
        final int numIndexThreads = 3;
        final int numPercolateThreads = 6;
        final int numPercolatorOperationsPerThread = 1000;

        final AtomicBoolean assertionFailure = new AtomicBoolean(false);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger runningPercolateThreads = new AtomicInteger(numPercolateThreads);
        final AtomicInteger type1 = new AtomicInteger();
        final AtomicInteger type2 = new AtomicInteger();
        final AtomicInteger type3 = new AtomicInteger();

        final AtomicInteger idGen = new AtomicInteger();

        Thread[] indexThreads = new Thread[numIndexThreads];
        for (int i = 0; i < numIndexThreads; i++) {
            final Random rand = new Random(getRandom().nextLong());
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        XContentBuilder onlyField1 = XContentFactory.jsonBuilder().startObject()
                                .field("query", termQuery("field1", "value")).endObject();
                        XContentBuilder onlyField2 = XContentFactory.jsonBuilder().startObject()
                                .field("query", termQuery("field2", "value")).endObject();
                        XContentBuilder field1And2 = XContentFactory.jsonBuilder().startObject()
                                .field("query", boolQuery().must(termQuery("field1", "value")).must(termQuery("field2", "value"))).endObject();

                        start.await();
                        while (runningPercolateThreads.get() > 0) {
                            Thread.sleep(100);
                            int x = rand.nextInt(3);
                            String id = Integer.toString(idGen.incrementAndGet());
                            IndexResponse response;
                            switch (x) {
                                case 0:
                                    response = client().prepareIndex("index", "_percolator", id)
                                            .setSource(onlyField1)
                                            .execute().actionGet();
                                    type1.incrementAndGet();
                                    break;
                                case 1:
                                    response = client().prepareIndex("index", "_percolator", id)
                                            .setSource(onlyField2)
                                            .execute().actionGet();
                                    type2.incrementAndGet();
                                    break;
                                case 2:
                                    response = client().prepareIndex("index", "_percolator", id)
                                            .setSource(field1And2)
                                            .execute().actionGet();
                                    type3.incrementAndGet();
                                    break;
                                default:
                                    throw new IllegalStateException("Illegal x=" + x);
                            }
                            assertThat(response.getId(), equalTo(id));
                            assertThat(response.getVersion(), equalTo(1l));
                        }
                    } catch (Throwable t) {
                        assertionFailure.set(true);
                        logger.error("Error in indexing thread...", t);
                    }
                }
            };
            indexThreads[i] = new Thread(r);
            indexThreads[i].start();
        }

        Thread[] percolateThreads = new Thread[numPercolateThreads];
        for (int i = 0; i < numPercolateThreads; i++) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        XContentBuilder onlyField1Doc = XContentFactory.jsonBuilder().startObject().startObject("doc")
                                .field("field1", "value")
                                .endObject().endObject();
                        XContentBuilder onlyField2Doc = XContentFactory.jsonBuilder().startObject().startObject("doc")
                                .field("field2", "value")
                                .endObject().endObject();
                        XContentBuilder field1AndField2Doc = XContentFactory.jsonBuilder().startObject().startObject("doc")
                                .field("field1", "value")
                                .field("field2", "value")
                                .endObject().endObject();
                        Random random = getRandom();
                        start.await();
                        for (int counter = 0; counter < numPercolatorOperationsPerThread; counter++) {
                            int x = random.nextInt(3);
                            int atLeastExpected;
                            PercolateResponse response;
                            switch (x) {
                                case 0:
                                    atLeastExpected = type1.get();
                                    response = client().preparePercolate("index", "type")
                                            .setSource(onlyField1Doc).execute().actionGet();
                                    assertThat(response.getShardFailures(), emptyArray());
                                    assertThat(response.getSuccessfulShards(), equalTo(response.getTotalShards()));
                                    assertThat(response.getMatches().length, greaterThanOrEqualTo(atLeastExpected));
                                    break;
                                case 1:
                                    atLeastExpected = type2.get();
                                    response = client().preparePercolate("index", "type")
                                            .setSource(onlyField2Doc).execute().actionGet();
                                    assertThat(response.getShardFailures(), emptyArray());
                                    assertThat(response.getSuccessfulShards(), equalTo(response.getTotalShards()));
                                    assertThat(response.getMatches().length, greaterThanOrEqualTo(atLeastExpected));
                                    break;
                                case 2:
                                    atLeastExpected = type3.get();
                                    response = client().preparePercolate("index", "type")
                                            .setSource(field1AndField2Doc).execute().actionGet();
                                    assertThat(response.getShardFailures(), emptyArray());
                                    assertThat(response.getSuccessfulShards(), equalTo(response.getTotalShards()));
                                    assertThat(response.getMatches().length, greaterThanOrEqualTo(atLeastExpected));
                                    break;
                            }
                        }
                    } catch (Throwable t) {
                        assertionFailure.set(true);
                        logger.error("Error in percolate thread...", t);
                    } finally {
                        runningPercolateThreads.decrementAndGet();
                    }
                }
            };
            percolateThreads[i] = new Thread(r);
            percolateThreads[i].start();
        }

        start.countDown();
        for (Thread thread : indexThreads) {
            thread.join();
        }
        for (Thread thread : percolateThreads) {
            thread.join();
        }

        assertThat(assertionFailure.get(), equalTo(false));
    }

    @Test
    public void testConcurrentAddingAndRemovingWhilePercolating() throws Exception {
        client().admin().indices().prepareCreate("index").setSettings(
                ImmutableSettings.settingsBuilder()
                        .put("index.number_of_shards", 2)
                        .put("index.number_of_replicas", 1)
                        .build()
        ).execute().actionGet();
        ensureGreen();
        final int numIndexThreads = 3;
        final int numberPercolateOperation = 100;

        final AtomicReference<Throwable> exceptionHolder = new AtomicReference<Throwable>(null);
        final AtomicInteger idGen = new AtomicInteger(0);
        final Set<String> liveIds = ConcurrentCollections.newConcurrentSet();
        final AtomicBoolean run = new AtomicBoolean(true);
        Thread[] indexThreads = new Thread[numIndexThreads];
        final Semaphore semaphore = new Semaphore(numIndexThreads, true);
        for (int i = 0; i < indexThreads.length; i++) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        XContentBuilder doc = XContentFactory.jsonBuilder().startObject()
                                .field("query", termQuery("field1", "value")).endObject();
                        while (run.get()) {
                            semaphore.acquire();
                            try {
                                if (!liveIds.isEmpty() && getRandom().nextInt(100) < 19) {
                                    String id;
                                    do {
                                        id = Integer.toString(randomInt(idGen.get()));
                                    } while (!liveIds.remove(id));

                                    DeleteResponse response = client().prepareDelete("index", "_percolator", id)
                                            .execute().actionGet();
                                    assertThat(response.getId(), equalTo(id));
                                    assertThat("doc[" + id + "] should have been deleted, but isn't", response.isNotFound(), equalTo(false));
                                } else {
                                    String id = Integer.toString(idGen.getAndIncrement());
                                    IndexResponse response = client().prepareIndex("index", "_percolator", id)
                                            .setSource(doc)
                                            .execute().actionGet();
                                    liveIds.add(id);
                                    assertThat(response.isCreated(), equalTo(true)); // We only add new docs
                                    assertThat(response.getId(), equalTo(id));
                                }
                            } finally {
                                semaphore.release();
                            }
                        }
                    } catch (InterruptedException iex) {
                        logger.error("indexing thread was interrupted...", iex);
                        run.set(false);
                    } catch (Throwable t) {
                        run.set(false);
                        exceptionHolder.set(t);
                        logger.error("Error in indexing thread...", t);
                    }
                }
            };
            indexThreads[i] = new Thread(r);
            indexThreads[i].start();
        }

        XContentBuilder percolateDoc = XContentFactory.jsonBuilder().startObject().startObject("doc")
                .field("field1", "value")
                .endObject().endObject();
        for (int counter = 0; counter < numberPercolateOperation; counter++) {
            Thread.sleep(5);
            semaphore.acquire(numIndexThreads);
            try {
                if (!run.get()) {
                    break;
                }
                int atLeastExpected = liveIds.size();
                PercolateResponse response = client().preparePercolate("index", "type")
                        .setSource(percolateDoc).execute().actionGet();
                assertThat(response.getShardFailures(), emptyArray());
                assertThat(response.getSuccessfulShards(), equalTo(response.getTotalShards()));
                assertThat(response.getMatches().length, equalTo(atLeastExpected));
            } finally {
                semaphore.release(numIndexThreads);
            }
        }
        run.set(false);
        for (Thread thread : indexThreads) {
            thread.join();
        }
        assertThat("exceptionHolder should have been empty, but holds: " + exceptionHolder.toString(), exceptionHolder.get(), nullValue());
    }

    @Override
    public Client client() {
        return client("node3");
    }

    private void ensureGreen() {
        ClusterHealthResponse actionGet = client().admin().cluster()
                .health(Requests.clusterHealthRequest().waitForGreenStatus().waitForEvents(Priority.LANGUID).waitForRelocatingShards(0)).actionGet();
        assertThat(actionGet.isTimedOut(), equalTo(false));
        assertThat(actionGet.getStatus(), equalTo(ClusterHealthStatus.GREEN));
    }

}
