package org.apache.solr.core;

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

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.StrUtils;
import org.noggit.CharArr;
import org.noggit.JSONParser;
import org.noggit.JSONWriter;
import org.noggit.ObjectBuilder;

/**
 * This class encapsulates the config overlay json file. It is immutable
 * and any edit operations performed on tbhis gives a new copy of the object
 * with the changed value
 */
public class ConfigOverlay implements MapSerializable {
  private final int znodeVersion;
  private final Map<String, Object> data;
  private Map<String, Object> props;
  private Map<String, Object> userProps;

  public ConfigOverlay(Map<String, Object> jsonObj, int znodeVersion) {
    if (jsonObj == null) jsonObj = Collections.EMPTY_MAP;
    this.znodeVersion = znodeVersion;
    data = Collections.unmodifiableMap(jsonObj);
    props = (Map<String, Object>) data.get("props");
    if (props == null) props = Collections.EMPTY_MAP;
    userProps = (Map<String, Object>) data.get("userProps");
    if (userProps == null) userProps = Collections.EMPTY_MAP;
  }

  public Object getXPathProperty(String xpath) {
    return getXPathProperty(xpath, true);
  }

  public Object getXPathProperty(String xpath, boolean onlyPrimitive) {
    List<String> hierarchy = checkEditable(xpath, true, false);
    if (hierarchy == null) return null;
    return getObjectByPath(props, onlyPrimitive, hierarchy);
  }

  public static Object getObjectByPath(Map root, boolean onlyPrimitive, List<String> hierarchy) {
    Map obj = root;
    for (int i = 0; i < hierarchy.size(); i++) {
      String s = hierarchy.get(i);
      if (i < hierarchy.size() - 1) {
        if (!(obj.get(s) instanceof Map)) return null;
        obj = (Map) obj.get(s);
        if (obj == null) return null;
      } else {
        Object val = obj.get(s);
        if (onlyPrimitive && val instanceof Map) {
          return null;
        }
        return val;
      }
    }

    return false;
  }

  public ConfigOverlay setUserProperty(String key, Object val) {
    Map copy = new LinkedHashMap(userProps);
    copy.put(key, val);
    Map<String, Object> jsonObj = new LinkedHashMap<>(this.data);
    jsonObj.put("userProps", copy);
    return new ConfigOverlay(jsonObj, znodeVersion);
  }

  public ConfigOverlay unsetUserProperty(String key) {
    if (!userProps.containsKey(key)) return this;
    Map copy = new LinkedHashMap(userProps);
    copy.remove(key);
    Map<String, Object> jsonObj = new LinkedHashMap<>(this.data);
    jsonObj.put("userProps", copy);
    return new ConfigOverlay(jsonObj, znodeVersion);
  }

  public ConfigOverlay setProperty(String name, Object val) {
    List<String> hierarchy = checkEditable(name, false, true);
    Map deepCopy = getDeepCopy(props);
    Map obj = deepCopy;
    for (int i = 0; i < hierarchy.size(); i++) {
      String s = hierarchy.get(i);
      if (i < hierarchy.size() - 1) {
        if (obj.get(s) == null || (!(obj.get(s) instanceof Map))) {
          obj.put(s, new LinkedHashMap<>());
        }
        obj = (Map) obj.get(s);
      } else {
        obj.put(s, val);
      }
    }

    Map<String, Object> jsonObj = new LinkedHashMap<>(this.data);
    jsonObj.put("props", deepCopy);

    return new ConfigOverlay(jsonObj, znodeVersion);
  }


  private Map getDeepCopy(Map map) {
    return (Map) ZkStateReader.fromJSON(ZkStateReader.toJSON(map));
  }

  public static final String NOT_EDITABLE = "''{0}'' is not an editable property";

  private List<String> checkEditable(String propName, boolean isXPath, boolean failOnError) {
    LinkedList<String> hierarchy = new LinkedList<>();
    if (!isEditableProp(propName, isXPath, hierarchy)) {
      if (failOnError)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, StrUtils.formatString(NOT_EDITABLE, propName));
      else return null;
    }
    return hierarchy;

  }

  public ConfigOverlay unsetProperty(String name) {
    List<String> hierarchy = checkEditable(name, false, true);
    Map deepCopy = getDeepCopy(props);
    Map obj = deepCopy;
    for (int i = 0; i < hierarchy.size(); i++) {
      String s = hierarchy.get(i);
      if (i < hierarchy.size() - 1) {
        if (obj.get(s) == null || (!(obj.get(s) instanceof Map))) {
          return this;
        }
        obj = (Map) obj.get(s);
      } else {
        obj.remove(s);
      }
    }

    Map<String, Object> jsonObj = new LinkedHashMap<>(this.data);
    jsonObj.put("props", deepCopy);

    return new ConfigOverlay(jsonObj, znodeVersion);
  }

  public byte[] toByteArray() {
    return ZkStateReader.toJSON(data);
  }


  public int getZnodeVersion() {
    return znodeVersion;
  }

  @Override
  public String toString() {
    CharArr out = new CharArr();
    try {
      new JSONWriter(out, 2).write(data);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return out.toString();
  }


  public static final String RESOURCE_NAME = "configoverlay.json";

  /*private static final Long STR_ATTR = 0L;
  private static final Long STR_NODE = 1L;
  private static final Long BOOL_ATTR = 10L;
  private static final Long BOOL_NODE = 11L;
  private static final Long INT_ATTR = 20L;
  private static final Long INT_NODE = 21L;
  private static final Long FLOAT_ATTR = 30L;
  private static final Long FLOAT_NODE = 31L;*/

  private static Map editable_prop_map;
  //The path maps to the xml xpath and value of 1 means it is a tag with a string value and value
  // of 0 means it is an attribute with string value
  public static final String MAPPING = "{" +
      "  updateHandler:{" +
      "    autoCommit:{" +
      "      maxDocs:20," +
      "      maxTime:20," +
      "      openSearcher:11}," +
      "    autoSoftCommit:{" +
      "      maxDocs:20," +
      "      maxTime:20}," +
      "    commitWithin:{softCommit:11}," +
      "    commitIntervalLowerBound:21," +
      "    indexWriter:{closeWaitsForMerges:11}}," +
      "  query:{" +
      "    filterCache:{" +
      "      class:0," +
      "      size:0," +
      "      initialSize:20," +
      "      autowarmCount:20," +
      "      maxRamMB:20," +
      "      regenerator:0}," +
      "    queryResultCache:{" +
      "      class:0," +
      "      size:20," +
      "      initialSize:20," +
      "      autowarmCount:20," +
      "      maxRamMB:20," +
      "      regenerator:0}," +
      "    documentCache:{" +
      "      class:0," +
      "      size:20," +
      "      initialSize:20," +
      "      autowarmCount:20," +
      "      regenerator:0}," +
      "    fieldValueCache:{" +
      "      class:0," +
      "      size:20," +
      "      initialSize:20," +
      "      autowarmCount:20," +
      "      regenerator:0}," +
      "    useFilterForSortedQuery:1," +
      "    queryResultWindowSize:1," +
      "    queryResultMaxDocsCached:1," +
      "    enableLazyFieldLoading:1," +
      "    boolTofilterOptimizer:1," +
      "    maxBooleanClauses:1}," +
      "  jmx:{" +
      "    agentId:0," +
      "    serviceUrl:0," +
      "    rootName:0}," +
      "  requestDispatcher:{" +
      "    handleSelect:0," +
      "    requestParsers:{" +
      "      multipartUploadLimitInKB:0," +
      "      formdataUploadLimitInKB:0," +
      "      enableRemoteStreaming:0," +
      "      addHttpRequestToContext:0}}}";

  static {
    try {
      editable_prop_map = (Map) new ObjectBuilder(new JSONParser(new StringReader(
          MAPPING))).getObject();
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "error parsing mapping ", e);
    }
  }

  public static boolean isEditableProp(String path, boolean isXpath, List<String> hierarchy) {
    return !(checkEditable(path, isXpath, hierarchy) == null);
  }


  public static Class checkEditable(String path, boolean isXpath, List<String> hierarchy) {
    List<String> parts = StrUtils.splitSmart(path, isXpath ? '/' : '.');
    Object obj = editable_prop_map;
    for (int i = 0; i < parts.size(); i++) {
      String part = parts.get(i);
      boolean isAttr = isXpath && part.startsWith("@");
      if (isAttr) {
        part = part.substring(1);
      }
      if (hierarchy != null) hierarchy.add(part);
      if (obj == null) return null;
      if (i == parts.size() - 1) {
        if (obj instanceof Map) {
          Map map = (Map) obj;
          Object o = map.get(part);
          return checkType(o, isXpath, isAttr);
        }
        return null;
      }
      obj = ((Map) obj).get(part);
    }
    return null;
  }

  static Class[] types = new Class[]{String.class, Boolean.class, Integer.class, Float.class};

  private static Class checkType(Object o, boolean isXpath, boolean isAttr) {
    if (o instanceof Long) {
      Long aLong = (Long) o;
      int ten = aLong.intValue() / 10;
      int one = aLong.intValue() % 10;
      if (isXpath && isAttr && one != 0) return null;
      return types[ten];
    } else {
      return null;
    }
  }

  public Map<String, String> getEditableSubProperties(String xpath) {
    Object o = getObjectByPath(props, false, StrUtils.splitSmart(xpath, '/'));
    if (o instanceof Map) {
      return (Map) o;
    } else {
      return null;
    }
  }

  public Map<String, Object> getUserProps() {
    return userProps;
  }

  @Override
  public Map<String, Object> toMap() {
    Map result = new LinkedHashMap();
    result.put(ZNODEVER, znodeVersion);
    result.putAll(data);
    return result;
  }

  public Map<String, Map> getNamedPlugins(String typ) {
    Map<String, Map> reqHandlers = (Map<String, Map>) data.get(typ);
    if (reqHandlers == null) return Collections.EMPTY_MAP;
    return Collections.unmodifiableMap(reqHandlers);
  }


  public ConfigOverlay addNamedPlugin(Map<String, Object> info, String typ) {
    Map dataCopy = RequestParams.getDeepCopy(data, 4);
    Map reqHandler = (Map) dataCopy.get(typ);
    if (reqHandler == null) dataCopy.put(typ, reqHandler = new LinkedHashMap());
    reqHandler.put(info.get(CoreAdminParams.NAME), info);
    return new ConfigOverlay(dataCopy, this.znodeVersion);
  }

  public ConfigOverlay deleteNamedPlugin(String name, String typ) {
    Map dataCopy = RequestParams.getDeepCopy(data, 4);
    Map reqHandler = (Map) dataCopy.get(typ);
    if (reqHandler == null) return this;
    reqHandler.remove(name);
    return new ConfigOverlay(dataCopy, this.znodeVersion);

  }

  public static final String ZNODEVER = "znodeVersion";
  public static final String NAME = "overlay";

  public static void main(String[] args) {
  }

}