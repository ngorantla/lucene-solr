package org.apache.solr.cloud;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.util.LuceneTestCase.BadApple;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrServer;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase.StopableThread;
import org.apache.solr.cloud.ChaosMonkeyNothingIsSafeTest.FullThrottleStopableIndexingThread;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.Diagnostics;
import org.apache.solr.update.SolrCmdDistributor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

@Slow
public class ChaosMonkeySafeLeaderTest extends AbstractFullDistribZkTestBase {
  private static final int FAIL_TOLERANCE = 20;
  
  private static final Integer RUN_LENGTH = Integer.parseInt(System.getProperty("solr.tests.cloud.cm.runlength", "-1"));

  @BeforeClass
  public static void beforeSuperClass() {
    schemaString = "schema15.xml";      // we need a string id
    SolrCmdDistributor.testing_errorHook = new Diagnostics.Callable() {
      @Override
      public void call(Object... data) {
        Exception e = (Exception) data[0];
        if (e == null) return;
        if (e.getMessage().contains("Timeout")) {
          Diagnostics.logThreadDumps("REQUESTING THREAD DUMP DUE TO TIMEOUT: " + e.getMessage());
        }
      }
    };
  }
  
  @AfterClass
  public static void afterSuperClass() {
    SolrCmdDistributor.testing_errorHook = null;
  }
  
  protected static final String[] fieldNames = new String[]{"f_i", "f_f", "f_d", "f_l", "f_dt"};
  protected static final RandVal[] randVals = new RandVal[]{rint, rfloat, rdouble, rlong, rdate};
  
  public String[] getFieldNames() {
    return fieldNames;
  }

  public RandVal[] getRandValues() {
    return randVals;
  }
  
  @Before
  @Override
  public void setUp() throws Exception {
    useFactory("solr.StandardDirectoryFactory");

    super.setUp();
    
    System.setProperty("numShards", Integer.toString(sliceCount));
  }
  
  @Override
  @After
  public void tearDown() throws Exception {
    System.clearProperty("numShards");
    super.tearDown();
    resetExceptionIgnores();
  }
  
  public ChaosMonkeySafeLeaderTest() {
    super();
    sliceCount = Integer.parseInt(System.getProperty("solr.tests.cloud.cm.slicecount", "3"));
    shardCount = Integer.parseInt(System.getProperty("solr.tests.cloud.cm.shardcount", "12"));
  }
  
  @Override
  public void doTest() throws Exception {
    
    handle.clear();
    handle.put("timestamp", SKIPVAL);
    
    // randomly turn on 1 seconds 'soft' commit
    randomlyEnableAutoSoftCommit();

    del("*:*");
    
    List<StopableIndexingThread> threads = new ArrayList<>();
    int threadCount = 2;
    int batchSize = 1;
    if (random().nextBoolean()) {
      batchSize = random().nextInt(98) + 2;
    }
    
    boolean pauseBetweenUpdates = TEST_NIGHTLY ? random().nextBoolean() : true;
    int maxUpdates = -1;
    if (!pauseBetweenUpdates) {
      maxUpdates = 1000 + random().nextInt(1000);
    } else {
      maxUpdates = 15000;
    }
    
    for (int i = 0; i < threadCount; i++) {
      StopableIndexingThread indexThread = new StopableIndexingThread(controlClient, cloudClient, Integer.toString(i), true, maxUpdates, batchSize, pauseBetweenUpdates); // random().nextInt(999) + 1
      threads.add(indexThread);
      indexThread.start();
    }
    
    chaosMonkey.startTheMonkey(false, 500);
    try {
      long runLength;
      if (RUN_LENGTH != -1) {
        runLength = RUN_LENGTH;
      } else {
        int[] runTimes = new int[] {5000, 6000, 10000, 25000, 27000, 30000,
            30000, 45000, 90000, 120000};
        runLength = runTimes[random().nextInt(runTimes.length - 1)];
      }
      
      Thread.sleep(runLength);
    } finally {
      chaosMonkey.stopTheMonkey();
    }
    
    for (StopableIndexingThread indexThread : threads) {
      indexThread.safeStop();
    }
    
    // wait for stop...
    for (StopableIndexingThread indexThread : threads) {
      indexThread.join();
    }
    
    // fails can happen, but cloud client should not easily fail
    for (StopableThread indexThread : threads) {
      if (indexThread instanceof StopableIndexingThread && !(indexThread instanceof FullThrottleStopableIndexingThread)) {
        assertFalse("There were too many update fails - we expect it can happen, but shouldn't easily", ((StopableIndexingThread) indexThread).getFailCount() > FAIL_TOLERANCE);
      }
    }
    
    // try and wait for any replications and what not to finish...

    Thread.sleep(2000);

    waitForThingsToLevelOut(180000);
    
    // even if things were leveled out, a jetty may have just been stopped or something
    // we wait again and wait to level out again to make sure the system is not still in flux
    
    Thread.sleep(3000);

    waitForThingsToLevelOut(180000);

    checkShardConsistency(batchSize == 1, true);
    
    if (VERBOSE) System.out.println("control docs:" + controlClient.query(new SolrQuery("*:*")).getResults().getNumFound() + "\n\n");
    
    // try and make a collection to make sure the overseer has survived the expiration and session loss

    // sometimes we restart zookeeper as well
    if (random().nextBoolean()) {
      zkServer.shutdown();
      zkServer = new ZkTestServer(zkServer.getZkDir(), zkServer.getPort());
      zkServer.run();
    }
    
    CloudSolrServer client = createCloudClient("collection1");
    try {
        createCollection(null, "testcollection",
            1, 1, 1, client, null, "conf1");

    } finally {
      client.shutdown();
    }
    List<Integer> numShardsNumReplicas = new ArrayList<>(2);
    numShardsNumReplicas.add(1);
    numShardsNumReplicas.add(1);
    checkForCollection("testcollection",numShardsNumReplicas, null);
  }
  
  // skip the randoms - they can deadlock...
  @Override
  protected void indexr(Object... fields) throws Exception {
    SolrInputDocument doc = new SolrInputDocument();
    addFields(doc, fields);
    addFields(doc, "rnd_b", true);
    indexDoc(doc);
  }

}
