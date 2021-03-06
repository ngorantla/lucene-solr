package org.apache.solr.common.cloud;

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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.noggit.JSONUtil;
import org.noggit.JSONWriter;

/**
 * Models a Collection in zookeeper (but that Java name is obviously taken, hence "DocCollection")
 */
public class DocCollection extends ZkNodeProps {
  public static final String DOC_ROUTER = "router";
  public static final String SHARDS = "shards";

  private final String name;
  private final Map<String, Slice> slices;
  private final Map<String, Slice> activeSlices;
  private final DocRouter router;

  private final Integer replicationFactor;
  private final Integer maxShardsPerNode;
  private final boolean autoAddReplicas;

  /**
   * @param name  The name of the collection
   * @param slices The logical shards of the collection.  This is used directly and a copy is not made.
   * @param props  The properties of the slice.  This is used directly and a copy is not made.
   */
  public DocCollection(String name, Map<String, Slice> slices, Map<String, Object> props, DocRouter router) {
    super( props==null ? Collections.<String,Object>emptyMap() : props);
    this.name = name;

    this.slices = slices;
    this.activeSlices = new HashMap<String, Slice>();
    
    if (props != null) {
      Object replicationFactorObject = (Object) props
          .get(ZkStateReader.REPLICATION_FACTOR);
      if (replicationFactorObject != null) {
        this.replicationFactor = Integer.parseInt(replicationFactorObject
            .toString());
      } else {
        this.replicationFactor = null;
      }
      Object maxShardsPerNodeObject = (Object) props
          .get(ZkStateReader.MAX_SHARDS_PER_NODE);
      if (maxShardsPerNodeObject != null) {
        this.maxShardsPerNode = Integer.parseInt(maxShardsPerNodeObject
            .toString());
      } else {
        this.maxShardsPerNode = null;
      }
      Object autoAddReplicasObject = (Object) props
          .get(ZkStateReader.AUTO_ADD_REPLICAS);
      if (autoAddReplicasObject != null) {
        this.autoAddReplicas = Boolean.parseBoolean(autoAddReplicasObject.toString());
      } else {
        this.autoAddReplicas = false;
      }
    } else {
      this.replicationFactor = null;
      this.maxShardsPerNode = null;
      this.autoAddReplicas = false;
    }

    Iterator<Map.Entry<String, Slice>> iter = slices.entrySet().iterator();

    while (iter.hasNext()) {
      Map.Entry<String, Slice> slice = iter.next();
      if (slice.getValue().getState().equals(Slice.ACTIVE))
        this.activeSlices.put(slice.getKey(), slice.getValue());
    }
    this.router = router;

    assert name != null && slices != null;
  }


  /**
   * Return collection name.
   */
  public String getName() {
    return name;
  }

  public Slice getSlice(String sliceName) {
    return slices.get(sliceName);
  }

  /**
   * Gets the list of all slices for this collection.
   */
  public Collection<Slice> getSlices() {
    return slices.values();
  }


  /**
   * Return the list of active slices for this collection.
   */
  public Collection<Slice> getActiveSlices() {
    return activeSlices.values();
  }

  /**
   * Get the map of all slices (sliceName->Slice) for this collection.
   */
  public Map<String, Slice> getSlicesMap() {
    return slices;
  }

  /**
   * Get the map of active slices (sliceName->Slice) for this collection.
   */
  public Map<String, Slice> getActiveSlicesMap() {
    return activeSlices;
  }

  /**
   * @return replication factor for this collection or null if no
   *         replication factor exists.
   */
  public Integer getReplicationFactor() {
    return replicationFactor;
  }
  
  public boolean getAutoAddReplicas() {
    return autoAddReplicas;
  }
  
  public Integer getMaxShardsPerNode() {
    return maxShardsPerNode;
  }

  public DocRouter getRouter() {
    return router;
  }

  @Override
  public String toString() {
    return "DocCollection("+name+")=" + JSONUtil.toJSON(this);
  }

  @Override
  public void write(JSONWriter jsonWriter) {
    LinkedHashMap<String, Object> all = new LinkedHashMap<String, Object>(slices.size() + 1);
    all.putAll(propMap);
    all.put(SHARDS, slices);
    jsonWriter.write(all);
  }
}
