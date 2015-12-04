package com.lucidworks.spark;

import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.lucidworks.spark.query.*;
import com.lucidworks.spark.util.SolrJsonSupport;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.*;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.feature.HashingTF;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.VectorUDT;
import org.apache.spark.mllib.linalg.MatrixUDT;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.Row;

public class SolrRDD implements Serializable {

  public static Logger log = Logger.getLogger(SolrRDD.class);

  public static final int DEFAULT_PAGE_SIZE = 1000;
  public static String SOLR_ZK_HOST_PARAM = "zkhost";
  public static String SOLR_COLLECTION_PARAM = "collection";
  public static String SOLR_QUERY_PARAM = "query";
  public static String SOLR_FIELD_LIST_PARAM = "fields";
  public static String SOLR_ROWS_PARAM = "rows";
  public static String SOLR_SPLIT_FIELD_PARAM = "split_field";
  public static String SOLR_SPLITS_PER_SHARD_PARAM = "splits_per_shard";
  public static String SOLR_PARALLEL_SHARDS = "parallel_shards";
  public static String PRESERVE_SCHEMA = "preserveschema";
  public static SolrQuery ALL_DOCS = toQuery(null);

  public static SolrQuery toQuery(String queryString) {

    if (queryString == null || queryString.length() == 0)
      queryString = "*:*";

    SolrQuery q = new SolrQuery();
    if (queryString.indexOf("=") == -1) {
      // no name-value pairs ... just assume this single clause is the q part
      q.setQuery(queryString);
    } else {
      NamedList<Object> params = new NamedList<Object>();
      for (NameValuePair nvp : URLEncodedUtils.parse(queryString, StandardCharsets.UTF_8)) {
        String value = nvp.getValue();
        if (value != null && value.length() > 0) {
          String name = nvp.getName();
          if ("sort".equals(name)) {
            if (value.indexOf(" ") == -1) {
              q.addSort(SolrQuery.SortClause.asc(value));
            } else {
              String[] split = value.split(" ");
              q.addSort(SolrQuery.SortClause.create(split[0], split[1]));
            }
          } else {
            params.add(name, value);
          }
        }
      }
      q.add(ModifiableSolrParams.toSolrParams(params));
    }

    Integer rows = q.getRows();
    if (rows == null)
      q.setRows(DEFAULT_PAGE_SIZE);

    String sorts = q.getSortField();
    if (sorts == null || sorts.isEmpty())
      q.addSort(SolrQuery.SortClause.asc(uniqueKey));

    return q;
  }

  /**
   * Iterates over the entire results set of a query (all hits).
   */
  public static class QueryResultsIterator extends PagedResultsIterator<SolrDocument> {

    public QueryResultsIterator(SolrClient solrServer, SolrQuery solrQuery, String cursorMark) {
      super(solrServer, solrQuery, cursorMark);
    }

    protected List<SolrDocument> processQueryResponse(QueryResponse resp) {
      return resp.getResults();
    }
  }

  /**
   * Returns an iterator over TermVectors
   */
  private class TermVectorIterator extends PagedResultsIterator<Vector> {

    private String field = null;
    private HashingTF hashingTF = null;

    private TermVectorIterator(SolrClient solrServer, SolrQuery solrQuery, String cursorMark, String field, int numFeatures) {
      super(solrServer, solrQuery, cursorMark);
      this.field = field;
      hashingTF = new HashingTF(numFeatures);
    }

    protected List<Vector> processQueryResponse(QueryResponse resp) {
      NamedList<Object> response = resp.getResponse();

      NamedList<Object> termVectorsNL = (NamedList<Object>)response.get("termVectors");
      if (termVectorsNL == null)
        throw new RuntimeException("No termVectors in response! " +
          "Please check your query to make sure it is requesting term vector information from Solr correctly.");

      List<Vector> termVectors = new ArrayList<Vector>(termVectorsNL.size());
      Iterator<Map.Entry<String, Object>> iter = termVectorsNL.iterator();
      while (iter.hasNext()) {
        Map.Entry<String, Object> next = iter.next();
        String nextKey = next.getKey();
        Object nextValue = next.getValue();
        if (nextValue instanceof NamedList) {
          NamedList nextList = (NamedList) nextValue;
          Object fieldTerms = nextList.get(field);
          if (fieldTerms != null && fieldTerms instanceof NamedList) {
            termVectors.add(SolrTermVector.newInstance(nextKey, hashingTF, (NamedList<Object>) fieldTerms));
          }
        }
      }

      SolrDocumentList docs = resp.getResults();
      totalDocs = docs.getNumFound();

      return termVectors;
    }
  }

  public static CloudSolrClient getSolrClient(String zkHost) {
    return SolrSupport.getSolrServer(zkHost);
  }

  protected String zkHost;
  protected String collection;
  protected static scala.collection.immutable.Map<String,String> config;
  protected static StructType schema;
  protected static String uniqueKey = "id";
  protected transient JavaSparkContext sc;

  public void setSc(JavaSparkContext jsc){
    sc = jsc;
  }
  public JavaSparkContext getSc() {
    return sc;
  }

  public SolrRDD(String collection) {
    this("localhost:9983", collection); // assume local embedded ZK if not supplied
  }

  public SolrRDD(String zkHost, String collection) {
      this(zkHost, collection, new scala.collection.immutable.HashMap<String,String>());
  }

  public SolrRDD(String zkHost, String collection, scala.collection.immutable.Map<String,String> config) {
    this.zkHost = zkHost;
    this.collection = collection;
    this.config = config;
    try {
        String solrBaseUrl = getSolrBaseUrl(zkHost);
        // Hit Solr Schema API to get base information
        String schemaUrl = solrBaseUrl+collection+"/schema";
        try {
            Map<String, Object> schemaMeta = SolrJsonSupport.getJson(SolrJsonSupport.getHttpClient(), schemaUrl, 2);
            this.uniqueKey = SolrJsonSupport.asString("/schema/uniqueKey", schemaMeta);
        } catch (SolrException solrExc) {
            log.warn("Can't get uniqueKey for " + collection+" due to solr: "+solrExc);
        }
    } catch (Exception exc) {
        log.warn("Can't get uniqueKey for " + collection+" due to: "+exc);
    }
    try {
        this.schema = getBaseSchema();
    } catch (Exception exc) {
        log.warn("Can't get schema for " + collection+" due to: "+exc);
    }
  }

    protected static String optionalParam(scala.collection.immutable.Map<String,String> config, String param, String defaultValue) {
      scala.Option<String> opt = config.get(param);
      String val = (opt != null && !opt.isEmpty()) ? (String)opt.get() : null;
      return (val == null || val.trim().isEmpty()) ? defaultValue : val;
    }

    protected static String requiredParam(scala.collection.immutable.Map<String,String> config, String param) {
      String val = optionalParam(config, param, null);
      if (val == null) throw new IllegalArgumentException(param+" parameter is required!");
      return val;
    }

    private static String getSolrBaseUrl(String zkHost) throws Exception {
        CloudSolrClient solrServer = getSolrClient(zkHost);
        Set<String> liveNodes = solrServer.getZkStateReader().getClusterState().getLiveNodes();
        if (liveNodes.isEmpty())
            throw new RuntimeException("No live nodes found for cluster: " + zkHost);
        String solrBaseUrl = solrServer.getZkStateReader().getBaseUrlForNodeName(liveNodes.iterator().next());
        if (!solrBaseUrl.endsWith("?"))
            solrBaseUrl += "/";
        return solrBaseUrl;
    }

    public String getCollection() {
        return collection;
    }

    public scala.collection.immutable.Map<String, String> getConfig() {
        return config;
    }

    public StructType getSchema() {
        return schema;
    }

  /**
   * Get a document by ID using real-time get
   */
  public JavaRDD<SolrDocument> get(JavaSparkContext jsc, final String docId) throws SolrServerException {
    CloudSolrClient cloudSolrServer = getSolrClient(zkHost);
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("collection", collection);
    params.set("qt", "/get");
    params.set("id", docId);
    QueryResponse resp = null;
    try {
      resp = cloudSolrServer.query(params);
    } catch (Exception exc) {
      if (exc instanceof SolrServerException) {
        throw (SolrServerException)exc;
      } else {
        throw new SolrServerException(exc);
      }
    }
    SolrDocument doc = (SolrDocument) resp.getResponse().get("doc");
    List<SolrDocument> list = (doc != null) ? Arrays.asList(doc) : new ArrayList<SolrDocument>();
    return jsc.parallelize(list, 1);
  }

  public JavaRDD<SolrDocument> query(JavaSparkContext jsc, final SolrQuery query, boolean useDeepPagingCursor) throws SolrServerException {
    if (useDeepPagingCursor)
      return queryDeep(jsc, query);

    query.set("collection", collection);
    CloudSolrClient cloudSolrServer = getSolrClient(zkHost);
    List<SolrDocument> results = new ArrayList<SolrDocument>();
    Iterator<SolrDocument> resultsIter = new QueryResultsIterator(cloudSolrServer, query, null);
    while (resultsIter.hasNext()) results.add(resultsIter.next());
    return jsc.parallelize(results, 1);
  }

  /**
   * Makes it easy to query from the Spark shell.
   */
  public JavaRDD<SolrDocument> query(SparkContext sc, String queryStr) throws SolrServerException {
    return query(sc, toQuery(queryStr));
  }

  public JavaRDD<SolrDocument> query(SparkContext sc, SolrQuery solrQuery) throws SolrServerException {
    return query(new JavaSparkContext(sc), solrQuery);
  }

  public JavaRDD<SolrDocument> query(JavaSparkContext jsc, SolrQuery solrQuery) throws SolrServerException {
      String splitFieldName = SolrRDD.optionalParam(config, SolrRDD.SOLR_SPLIT_FIELD_PARAM, null);
      int splitsPerShard = 1;
      if (splitFieldName != null)
        splitsPerShard = Integer.parseInt(SolrRDD.optionalParam(config, SolrRDD.SOLR_SPLITS_PER_SHARD_PARAM, "20"));
      boolean parallelShards = Boolean.parseBoolean(SolrRDD.optionalParam(config, SolrRDD.SOLR_PARALLEL_SHARDS, "true"));
      return parallelShards ?
              queryShards(jsc, solrQuery, splitFieldName, splitsPerShard) :
              queryDeep(jsc, solrQuery);
  }

  public JavaRDD<SolrDocument> queryShards(JavaSparkContext jsc, final SolrQuery origQuery) throws SolrServerException {
    // first get a list of replicas to query for this collection
    List<String> shards = buildShardList(getSolrClient(zkHost));

    final SolrQuery query = origQuery.getCopy();

    // we'll be directing queries to each shard, so we don't want distributed
    query.set("distrib", false);
    query.set("collection", collection);
    query.setStart(0);
    if (query.getRows() == null)
      query.setRows(DEFAULT_PAGE_SIZE); // default page size

    String sorts = query.getSortField();
    if (sorts == null || sorts.isEmpty())
        query.addSort(SolrQuery.SortClause.asc(uniqueKey));

    // parallelize the requests to the shards
    JavaRDD<SolrDocument> docs = jsc.parallelize(shards, shards.size()).flatMap(
      new FlatMapFunction<String, SolrDocument>() {
        public Iterable<SolrDocument> call(String shardUrl) throws Exception {
          return new StreamingResultsIterator(SolrSupport.getHttpSolrClient(shardUrl), query, "*");
        }
      }
    );
    return docs;
  }

  public JavaRDD<ShardSplit> splitShard(JavaSparkContext jsc, final SolrQuery origQuery, List<String> shards, final String splitFieldName, final int splitsPerShard) {
    final SolrQuery query = origQuery.getCopy();
    query.set("distrib", false);
    query.set("collection", collection);
    query.setStart(0);
    if (query.getRows() == null)
      query.setRows(DEFAULT_PAGE_SIZE); // default page size

    String sorts = query.getSortField();
    if (sorts == null || sorts.isEmpty())
        query.addSort(SolrQuery.SortClause.asc(uniqueKey));

    // get field type of split field
    final DataType fieldDataType;
    if ("_version_".equals(splitFieldName)) {
      fieldDataType = DataTypes.LongType;
    } else {
      Map<String,SolrFieldMeta> fieldMetaMap = getFieldTypes(new String[]{splitFieldName}, shards.get(0), collection);
      SolrFieldMeta solrFieldMeta = fieldMetaMap.get(splitFieldName);
      if (solrFieldMeta != null) {
        String fieldTypeClass = solrFieldMeta.fieldTypeClass;
        fieldDataType = solrDataTypes.get(fieldTypeClass);
      } else {
        log.warn("No field metadata found for "+splitFieldName+", assuming it is a String!");
        fieldDataType = DataTypes.StringType;
      }
      if (fieldDataType == null)
        throw new IllegalArgumentException("Cannot determine DataType for split field "+splitFieldName);
    }

    JavaRDD<ShardSplit> splitsRDD = jsc.parallelize(shards, shards.size()).flatMap(new FlatMapFunction<String, ShardSplit>() {
      public Iterable<ShardSplit> call(String shardUrl) throws Exception {

        ShardSplitStrategy splitStrategy = null;
        if (fieldDataType == DataTypes.LongType || fieldDataType == DataTypes.IntegerType) {
          splitStrategy = new NumberFieldShardSplitStrategy();
        } else if (fieldDataType == DataTypes.StringType) {
          splitStrategy = new StringFieldShardSplitStrategy();
        } else {
          throw new IllegalArgumentException("Can only split shards on fields of type: long, int, or string!");
        }
        List<ShardSplit> splits =
            splitStrategy.getSplits(shardUrl, query, splitFieldName, splitsPerShard);

        log.info("Found "+splits.size()+" splits for "+splitFieldName+": "+splits);

        return splits;
      }
    });
    return splitsRDD;
  }

  public JavaRDD<SolrDocument> queryShards(JavaSparkContext jsc, final SolrQuery origQuery, final String splitFieldName, final int splitsPerShard) throws SolrServerException {
    // if only doing 1 split per shard, then queryShards does that already
    if (splitFieldName == null || splitsPerShard <= 1)
      return queryShards(jsc, origQuery);

    long timerDiffMs = 0L;
    long timerStartMs = 0L;

    // first get a list of replicas to query for this collection
    List<String> shards = buildShardList(getSolrClient(zkHost));

    timerStartMs = System.currentTimeMillis();

    // we'll be directing queries to each shard, so we don't want distributed
    JavaRDD<ShardSplit> splitsRDD = splitShard(jsc, origQuery, shards, splitFieldName, splitsPerShard);
    List<ShardSplit> splits = splitsRDD.collect();
    timerDiffMs = (System.currentTimeMillis() - timerStartMs);
    log.info("Collected "+splits.size()+" splits, took "+timerDiffMs+"ms");

    // parallelize the requests to the shards
    JavaRDD<SolrDocument> docs = jsc.parallelize(splits, splits.size()).flatMap(
      new FlatMapFunction<ShardSplit, SolrDocument>() {
        public Iterable<SolrDocument> call(ShardSplit split) throws Exception {
          return new StreamingResultsIterator(SolrSupport.getHttpSolrClient(split.getShardUrl()), split.getSplitQuery(), "*");
        }
      }
    );
    return docs;
  }

  public JavaRDD<Vector> queryTermVectors(JavaSparkContext jsc, final SolrQuery query, final String field, final int numFeatures) throws SolrServerException {
    // first get a list of replicas to query for this collection
    List<String> shards = buildShardList(getSolrClient(zkHost));

    if (query.getRequestHandler() == null) {
      query.setRequestHandler("/tvrh");
    }
    query.set("shards.qt", query.getRequestHandler());

    query.set("tv.fl", field);
    query.set("fq", field + ":[* TO *]"); // terms field not null!
    query.set("tv.tf_idf", "true");

    // we'll be directing queries to each shard, so we don't want distributed
    query.set("distrib", false);
    query.set("collection", collection);
    query.setStart(0);
    if (query.getRows() == null)
      query.setRows(DEFAULT_PAGE_SIZE); // default page size

    String sorts = query.getSortField();
    if (sorts == null || sorts.isEmpty())
        query.addSort(SolrQuery.SortClause.asc(uniqueKey));

    // parallelize the requests to the shards
    JavaRDD<Vector> docs = jsc.parallelize(shards, shards.size()).flatMap(
      new FlatMapFunction<String, Vector>() {
        public Iterable<Vector> call(String shardUrl) throws Exception {
          return new TermVectorIterator(SolrSupport.getHttpSolrClient(shardUrl), query, "*", field, numFeatures);
        }
      }
    );
    return docs;
  }

  // TODO: need to build up a LBSolrServer here with all possible replicas

  public List<String> buildShardList(CloudSolrClient cloudSolrServer) {
    ZkStateReader zkStateReader = cloudSolrServer.getZkStateReader();

    ClusterState clusterState = zkStateReader.getClusterState();

    String[] collections = null;
    if (clusterState.hasCollection(collection)) {
      collections = new String[]{collection};
    } else {
      // might be a collection alias?
      Aliases aliases = zkStateReader.getAliases();
      String aliasedCollections = aliases.getCollectionAlias(collection);
      if (aliasedCollections == null)
        throw new IllegalArgumentException("Collection " + collection + " not found!");
      collections = aliasedCollections.split(",");
    }

    Set<String> liveNodes = clusterState.getLiveNodes();
    Random random = new Random(5150);

    List<String> shards = new ArrayList<String>();
    for (String coll : collections) {
      for (Slice slice : clusterState.getSlices(coll)) {
        List<String> replicas = new ArrayList<String>();
        for (Replica r : slice.getReplicas()) {
          if (r.getState().equals(Replica.State.ACTIVE)) {
            ZkCoreNodeProps replicaCoreProps = new ZkCoreNodeProps(r);
            if (liveNodes.contains(replicaCoreProps.getNodeName()))
              replicas.add(replicaCoreProps.getCoreUrl());
          }
        }
        int numReplicas = replicas.size();
        if (numReplicas == 0)
          throw new IllegalStateException("Shard " + slice.getName() + " in collection "+
                  coll+" does not have any active replicas!");

        String replicaUrl = (numReplicas == 1) ? replicas.get(0) : replicas.get(random.nextInt(replicas.size()));
        shards.add(replicaUrl);
      }
    }
    return shards;
  }

  public JavaRDD<SolrDocument> queryDeep(JavaSparkContext jsc, final SolrQuery origQuery) throws SolrServerException {
    return queryDeep(jsc, origQuery, 36);
  }

  public JavaRDD<SolrDocument> queryDeep(JavaSparkContext jsc, final SolrQuery origQuery, final int maxPartitions) throws SolrServerException {

    final SolrClient solrClient = getSolrClient(zkHost);
    final SolrQuery query = origQuery.getCopy();
    query.set("collection", collection);
    query.setStart(0);
    if (query.getRows() == null)
      query.setRows(DEFAULT_PAGE_SIZE); // default page size

    String sorts = query.getSortField();
    if (sorts == null || sorts.isEmpty())
        query.addSort(SolrQuery.SortClause.asc(uniqueKey));

    long startMs = System.currentTimeMillis();
    List<String> cursors = collectCursors(solrClient, query, true);
    long tookMs = System.currentTimeMillis() - startMs;
    log.info("Took "+tookMs+"ms to collect "+cursors.size()+" cursor marks");
    int numPartitions = Math.min(maxPartitions,cursors.size());

    JavaRDD<String> cursorJavaRDD = jsc.parallelize(cursors, numPartitions);
    // now we need to execute all the cursors in parallel
    JavaRDD<SolrDocument> docs = cursorJavaRDD.flatMap(
      new FlatMapFunction<String, SolrDocument>() {
        public Iterable<SolrDocument> call(String cursorMark) throws Exception {
          return querySolr(getSolrClient(zkHost), query, 0, cursorMark).getResults();
        }
      }
    );
    return docs;
  }

  protected List<String> collectCursors(final SolrClient solrClient, final SolrQuery origQuery) throws SolrServerException {
    return collectCursors(solrClient, origQuery, false);
  }

  protected List<String> collectCursors(final SolrClient solrClient, final SolrQuery origQuery, final boolean distrib) throws SolrServerException {
    List<String> cursors = new ArrayList<String>();

    final SolrQuery query = origQuery.getCopy();
    // tricky - if distrib == false, then set the param, otherwise, leave it out (default is distrib=true)
    if (!distrib) {
      query.set("distrib", false);
    } else {
      query.remove("distrib");
    }
    query.setFields(uniqueKey);

    String nextCursorMark = "*";
    while (true) {
      cursors.add(nextCursorMark);
      query.set("cursorMark", nextCursorMark);

      QueryResponse resp = null;
      try {
        resp = solrClient.query(query);
      } catch (Exception exc) {
        if (exc instanceof SolrServerException) {
          throw (SolrServerException)exc;
        } else {
          throw new SolrServerException(exc);
        }
      }

      nextCursorMark = resp.getNextCursorMark();
      if (nextCursorMark == null || resp.getResults().isEmpty())
        break;
    }

    return cursors;
  }

  private static final Map<String,DataType> solrDataTypes = new HashMap<String, DataType>();
  static {
    solrDataTypes.put("solr.StrField", DataTypes.StringType);
    solrDataTypes.put("solr.TextField", DataTypes.StringType);
    solrDataTypes.put("solr.BoolField", DataTypes.BooleanType);
    solrDataTypes.put("solr.TrieIntField", DataTypes.IntegerType);
    solrDataTypes.put("solr.TrieLongField", DataTypes.LongType);
    solrDataTypes.put("solr.TrieFloatField", DataTypes.FloatType);
    solrDataTypes.put("solr.TrieDoubleField", DataTypes.DoubleType);
    solrDataTypes.put("solr.TrieDateField", DataTypes.TimestampType);
  }

  public DataFrame asTempTable(SQLContext sqlContext, String queryString, String tempTable) throws Exception {
    SolrQuery solrQuery = toQuery(queryString);
    DataFrame rows = applySchema(sqlContext, solrQuery, query(sqlContext.sparkContext(), solrQuery));
    rows.registerTempTable(tempTable);
    return rows;
  }

  public DataFrame queryForRows(SQLContext sqlContext, String queryString) throws Exception {
    SolrQuery solrQuery = toQuery(queryString);
    return applySchema(sqlContext, solrQuery, query(sqlContext.sparkContext(), solrQuery));
  }

  public DataFrame applySchema(SQLContext sqlContext, SolrQuery query, JavaRDD<SolrDocument> docs) throws Exception {
    // now convert each SolrDocument to a Row object
    StructType schema = getQuerySchema(query);
    JavaRDD<Row> rows = toRows(schema, docs);
    return sqlContext.applySchema(rows, schema);
  }

  public JavaRDD<Row> toRows(StructType schema, JavaRDD<SolrDocument> docs) {
    final StructField[] fields = schema.fields();
    JavaRDD<Row> rows = docs.map(new Function<SolrDocument, Row>() {
      public Row call(SolrDocument doc) throws Exception {
        Object[] vals = new Object[fields.length];
        for (int f = 0; f < fields.length; f++) {
          StructField field = fields[f];
          Metadata meta = field.metadata();
          Boolean isMultiValued = meta.contains("multiValued") ? meta.getBoolean("multiValued") : false;
          Object fieldValue = isMultiValued ? doc.getFieldValues(field.name()) : doc.getFieldValue(field.name());;
          if (fieldValue != null) {
            if (fieldValue instanceof Collection) {
              vals[f] = ((Collection) fieldValue).toArray();
            } else if (fieldValue instanceof Date) {
              vals[f] = new java.sql.Timestamp(((Date) fieldValue).getTime());
            } else {
              vals[f] = fieldValue;
            }
          }
        }
        return RowFactory.create(vals);
      }
    });
    return rows;
  }

  private StructType getBaseSchema() throws Exception {
      String solrBaseUrl = getSolrBaseUrl(zkHost);
      Map<String,SolrFieldMeta> fieldTypeMap = getSchemaFields(solrBaseUrl, collection);

      List<StructField> listOfFields = new ArrayList<StructField>();
      for (Map.Entry<String, SolrFieldMeta> field : fieldTypeMap.entrySet()) {
        String fieldName = field.getKey();
        SolrFieldMeta fieldMeta = field.getValue();
        MetadataBuilder metadata = new MetadataBuilder();
        metadata.putString("name", field.getKey());
        DataType dataType = (fieldMeta.fieldTypeClass != null) ? solrDataTypes.get(fieldMeta.fieldTypeClass) : null;
        if (dataType == null) dataType = DataTypes.StringType;

        if (fieldMeta.isMultiValued) {
          dataType = new ArrayType(dataType, true);
          metadata.putBoolean("multiValued", fieldMeta.isMultiValued);
        }
        if (fieldMeta.isRequired) metadata.putBoolean("required", fieldMeta.isRequired);
        if (fieldMeta.isDocValues) metadata.putBoolean("docValues", fieldMeta.isDocValues);
        if (fieldMeta.isStored) metadata.putBoolean("stored", fieldMeta.isStored);
        if (fieldMeta.fieldType != null) metadata.putString("type", fieldMeta.fieldType);
        if (fieldMeta.dynamicBase != null) metadata.putString("dynamicBase", fieldMeta.dynamicBase);
        if (fieldMeta.fieldTypeClass != null) metadata.putString("class", fieldMeta.fieldTypeClass);
        listOfFields.add(DataTypes.createStructField(fieldName, dataType, !fieldMeta.isRequired, metadata.build()));
      }

      return DataTypes.createStructType(listOfFields);
  }

    // derive a schema for a specific query from the full collection schema
    public StructType deriveQuerySchema(String[] fields) {
        Map<String, StructField> fieldMap = new HashMap<String, StructField>();
        for (StructField f : schema.fields()) fieldMap.put(f.name(), f);
        List<StructField> listOfFields = new ArrayList<StructField>();
        for (String field : fields) listOfFields.add(fieldMap.get(field));
        return DataTypes.createStructType(listOfFields);
    }

    public StructType getQuerySchema(SolrQuery query) throws Exception {
        String fieldList = query.getFields();
        if (fieldList != null && !fieldList.isEmpty()) {
            return deriveQuerySchema(fieldList.split(","));
        }
        return schema;
    }
    



  private static class SolrFieldMeta {
    String fieldType;
    String dynamicBase;
    boolean isRequired;
    boolean isMultiValued;
    boolean isDocValues;
    boolean isStored;
    String fieldTypeClass;
  }

  private static Map<String, SolrFieldMeta> getSchemaFields(String solrBaseUrl, String collection) {
      String lukeUrl = solrBaseUrl+collection+"/admin/luke?numTerms=0";
      // collect mapping of Solr field to type
      Map<String,SolrFieldMeta> schemaFieldMap = new HashMap<String,SolrFieldMeta>();
      try {
          try {
              Map<String, Object> adminMeta = SolrJsonSupport.getJson(SolrJsonSupport.getHttpClient(), lukeUrl, 2);
              Map<String, Object> fieldsMap = SolrJsonSupport.asMap("/fields", adminMeta);
              Set<String> fieldNamesSet = fieldsMap.keySet();
              schemaFieldMap = getFieldTypes(fieldNamesSet.toArray(new String[fieldNamesSet.size()]), solrBaseUrl, collection);
          } catch (SolrException solrExc) {
              log.warn("Can't get field types for " + collection+" due to: "+solrExc);
          }
      } catch (Exception exc) {
          log.warn("Can't get schema fields for " + collection + " due to: "+exc);
      }
      return schemaFieldMap;
  }

  private static Map<String,SolrFieldMeta> getFieldTypes(String[] fields, String solrBaseUrl, String collection) {

    // specific field list
    StringBuilder sb = new StringBuilder();
    if (fields.length > 0) sb.append("&fl=");
    for (int f=0; f < fields.length; f++) {
      if (f > 0) sb.append(",");
      sb.append(fields[f]);
    }
    String fl = sb.toString();

    String fieldsUrl = solrBaseUrl+collection+"/schema/fields?showDefaults=true&includeDynamic=true"+fl;
    List<Map<String, Object>> fieldInfoFromSolr = null;
    try {
      Map<String, Object> allFields =
              SolrJsonSupport.getJson(SolrJsonSupport.getHttpClient(), fieldsUrl, 2);
      fieldInfoFromSolr = (List<Map<String, Object>>)allFields.get("fields");
    } catch (Exception exc) {
      String errMsg = "Can't get field metadata from Solr using request "+fieldsUrl+" due to: " + exc;
      log.error(errMsg);
      if (exc instanceof RuntimeException) {
        throw (RuntimeException)exc;
      } else {
        throw new RuntimeException(errMsg, exc);
      }
    }

    // avoid looking up field types more than once
    Map<String,String> fieldTypeToClassMap = new HashMap<String,String>();

    // collect mapping of Solr field to type
    Map<String,SolrFieldMeta> fieldTypeMap = new HashMap<String,SolrFieldMeta>();
    for (String field : fields) {

      if (fieldTypeMap.containsKey(field))
        continue;

      SolrFieldMeta tvc = null;
      for (Map<String,Object> map : fieldInfoFromSolr) {
        String fieldName = (String)map.get("name");
        if (field.equals(fieldName)) {
          tvc = new SolrFieldMeta();
          tvc.fieldType = (String)map.get("type");
          Object required = map.get("required");
          if (required != null && required instanceof Boolean) {
            tvc.isRequired = ((Boolean)required).booleanValue();
          } else {
            tvc.isRequired = "true".equals(String.valueOf(required));
          }
          Object multiValued = map.get("multiValued");
          if (multiValued != null && multiValued instanceof Boolean) {
            tvc.isMultiValued = ((Boolean)multiValued).booleanValue();
          } else {
            tvc.isMultiValued = "true".equals(String.valueOf(multiValued));
          }
          Object docValues = map.get("docValues");
          if (docValues != null && docValues instanceof Boolean) {
            tvc.isDocValues = ((Boolean)docValues).booleanValue();
          } else {
            tvc.isDocValues = "true".equals(String.valueOf(docValues));
          }
          Object stored = map.get("stored");
          if (stored != null && stored instanceof Boolean) {
            tvc.isStored = ((Boolean)stored).booleanValue();
          } else {
            tvc.isStored = "true".equals(String.valueOf(stored));
          }
          Object dynamicBase = map.get("dynamicBase");
          if (dynamicBase != null && dynamicBase instanceof String) {
            tvc.dynamicBase = (String)dynamicBase;
          }
        }
      }

      if (tvc == null || tvc.fieldType == null) {
        String errMsg = "Can't figure out field type for field: " + field + ". Check you Solr schema and retry.";
        log.error(errMsg);
        throw new RuntimeException(errMsg);
      }

      String fieldTypeClass = fieldTypeToClassMap.get(tvc.fieldType);
      if (fieldTypeClass != null) {
        tvc.fieldTypeClass = fieldTypeClass;
      } else {
        String fieldTypeUrl = solrBaseUrl+collection+"/schema/fieldtypes/"+tvc.fieldType;
        try {
          Map<String, Object> fieldTypeMeta =
                  SolrJsonSupport.getJson(SolrJsonSupport.getHttpClient(), fieldTypeUrl, 2);
          tvc.fieldTypeClass = SolrJsonSupport.asString("/fieldType/class", fieldTypeMeta);
          fieldTypeToClassMap.put(tvc.fieldType, tvc.fieldTypeClass);
        } catch (Exception exc) {
          String errMsg = "Can't get field type metadata for "+tvc.fieldType+" from Solr due to: " + exc;
          log.error(errMsg);
          if (exc instanceof RuntimeException) {
            throw (RuntimeException)exc;
          } else {
            throw new RuntimeException(errMsg, exc);
          }
        }
      }

      if (!tvc.isStored) {
        log.warn("Can't retrieve an index only field: " + field);
        tvc = null;
      }
      if (tvc != null) {
        fieldTypeMap.put(field, tvc);
      }
    }

    return fieldTypeMap;
  }

  public Map<String,Double> getLabels(String labelField) throws SolrServerException {
    SolrQuery solrQuery = new SolrQuery("*:*");
    solrQuery.setRows(0);
    solrQuery.set("collection", collection);
    solrQuery.addFacetField(labelField);
    solrQuery.setFacetMinCount(1);
    QueryResponse qr = querySolr(getSolrClient(zkHost), solrQuery, 0, null);
    List<String> values = new ArrayList<>();
    for (FacetField.Count f : qr.getFacetField(labelField).getValues()) {
      values.add(f.getName());
    }

    Collections.sort(values);
    final Map<String,Double> labelMap = new HashMap<>();
    double d = 0d;
    for (String label : values) {
      labelMap.put(label, new Double(d));
      d += 1d;
    }

    return labelMap;
  }

  public static QueryResponse querySolr(SolrClient solrServer, SolrQuery solrQuery, int startIndex, String cursorMark) throws SolrServerException {
    return querySolr(solrServer, solrQuery, startIndex, cursorMark, null);
  }

  public static QueryResponse querySolr(SolrClient solrServer, SolrQuery solrQuery, int startIndex, String cursorMark, StreamingResponseCallback callback) throws SolrServerException {
    QueryResponse resp = null;
    try {
      if (cursorMark != null) {
        solrQuery.setStart(0);
        solrQuery.set("cursorMark", cursorMark);
      } else {
        solrQuery.setStart(startIndex);
      }

      Integer rows = solrQuery.getRows();
      if (rows == null)
          solrQuery.setRows(DEFAULT_PAGE_SIZE);

      String sorts = solrQuery.getSortField();
      if (sorts == null || sorts.isEmpty())
          solrQuery.addSort(SolrQuery.SortClause.asc(uniqueKey));

      if (callback != null) {
        resp = solrServer.queryAndStreamResponse(solrQuery, callback);
      } else {
        resp = solrServer.query(solrQuery);
      }
    } catch (Exception exc) {

      log.error("Query ["+solrQuery+"] failed due to: "+exc);

      // re-try once in the event of a communications error with the server
      Throwable rootCause = SolrException.getRootCause(exc);
      boolean wasCommError =
        (rootCause instanceof ConnectException ||
          rootCause instanceof IOException ||
          rootCause instanceof SocketException);
      if (wasCommError) {
        try {
          Thread.sleep(2000L);
        } catch (InterruptedException ie) {
          Thread.interrupted();
        }

        try {
          if (callback != null) {
            resp = solrServer.queryAndStreamResponse(solrQuery, callback);
          } else {
            resp = solrServer.query(solrQuery);
          }
        } catch (Exception excOnRetry) {
          if (excOnRetry instanceof SolrServerException) {
            throw (SolrServerException)excOnRetry;
          } else {
            throw new SolrServerException(excOnRetry);
          }
        }
      } else {
        if (exc instanceof SolrServerException) {
          throw (SolrServerException)exc;
        } else {
          throw new SolrServerException(exc);
        }
      }
    }

    return resp;
  }

  public static DataType getsqlDataType(String s) {
    if (s.toLowerCase().equals("double")) {
      return DataTypes.DoubleType;
    }
    if (s.toLowerCase().equals("byte")) {
      return DataTypes.ByteType;
    }
    if (s.toLowerCase().equals("short")) {
      return DataTypes.ShortType;
    }
    if (((s.toLowerCase().equals("int")) || (s.toLowerCase().equals("integer")))) {
      return DataTypes.IntegerType;
    }
    if (s.toLowerCase().equals("long")) {
      return DataTypes.LongType;
    }
    if (s.toLowerCase().equals("String")) {
      return DataTypes.StringType;
    }
    if (s.toLowerCase().equals("boolean")) {
      return DataTypes.BooleanType;
    }
    if (s.toLowerCase().equals("timestamp")) {
      return DataTypes.TimestampType;
    }
    if (s.toLowerCase().equals("date")) {
      return DataTypes.DateType;
    }
    if (s.toLowerCase().equals("vector")) {
      return new VectorUDT();
    }
    if (s.toLowerCase().equals("matrix")) {
      return new MatrixUDT();
    }
    if (s.contains(":") && s.split(":")[0].toLowerCase().equals("array")) {
      return getArrayTypeRecurse(s,0);
    }
    return DataTypes.StringType;
  }

  public static DataType getArrayTypeRecurse(String s, int fromIdx) {
    if (s.contains(":") && s.split(":")[1].toLowerCase().equals("array")) {
      fromIdx = s.indexOf(":", fromIdx);
      s = s.substring(fromIdx+1, s.length());
      return DataTypes.createArrayType(getArrayTypeRecurse(s,fromIdx));
    }
    return DataTypes.createArrayType(getsqlDataType(s.split(":")[1]));
  }

  public static final class PivotField implements Serializable {
    public final String solrField;
    public final String prefix;
    public final String otherSuffix;
    public final int maxCols;

    public PivotField(String solrField, String prefix) {
      this(solrField, prefix, 10);
    }

    public PivotField(String solrField, String prefix, int maxCols) {
      this(solrField, prefix, maxCols, "other");
    }

    public PivotField(String solrField, String prefix, int maxCols, String otherSuffix) {
      this.solrField = solrField;
      this.prefix = prefix;
      this.maxCols = maxCols;
      this.otherSuffix = otherSuffix;
    }
  }

  /**
   * Allows you to pivot a categorical field into multiple columns that can be aggregated into counts, e.g.
   * a field holding HTTP method (http_verb=GET) can be converted into: http_method_get=1, which is a common
   * task when creating aggregations.
   */
  public DataFrame withPivotFields(final DataFrame solrData, final PivotField[] pivotFields) throws IOException, SolrServerException {

    final StructType schemaWithPivots = toPivotSchema(solrData.schema(), pivotFields);

    JavaRDD<Row> withPivotFields = solrData.javaRDD().map(new Function<Row, Row>() {
      @Override
      public Row call(Row row) throws Exception {
        Object[] fields = new Object[schemaWithPivots.size()];
        for (int c=0; c < row.length(); c++)
          fields[c] = row.get(c);

        for (PivotField pf : pivotFields)
          SolrRDD.fillPivotFieldValues(row.getString(row.fieldIndex(pf.solrField)), fields, schemaWithPivots, pf.prefix);

        return RowFactory.create(fields);
      }
    });

    return solrData.sqlContext().createDataFrame(withPivotFields, schemaWithPivots);
  }

  public StructType toPivotSchema(final StructType baseSchema, final PivotField[] pivotFields) throws IOException, SolrServerException {
    List<StructField> pivotSchemaFields = new ArrayList<>();
    pivotSchemaFields.addAll(Arrays.asList(baseSchema.fields()));
    for (PivotField pf : pivotFields) {
      for (StructField sf : getPivotSchema(pf.solrField, pf.maxCols, pf.prefix, pf.otherSuffix)) {
        pivotSchemaFields.add(sf);
      }
    }
    return DataTypes.createStructType(pivotSchemaFields);
  }

  public List<StructField> getPivotSchema(String fieldName, int maxCols, String fieldPrefix, String otherName) throws IOException, SolrServerException {
    final List<StructField> listOfFields = new ArrayList<StructField>();
    SolrQuery q = new SolrQuery("*:*");
    q.set("collection", collection);
    q.setFacet(true);
    q.addFacetField(fieldName);
    q.setFacetMinCount(1);
    q.setFacetLimit(maxCols);
    q.setRows(0);
    FacetField ff = querySolr(getSolrClient(zkHost), q, 0, null).getFacetField(fieldName);
    for (FacetField.Count f : ff.getValues()) {
      listOfFields.add(DataTypes.createStructField(fieldPrefix+f.getName().toLowerCase(), DataTypes.IntegerType, false));
    }
    if (otherName != null) {
      listOfFields.add(DataTypes.createStructField(fieldPrefix+otherName, DataTypes.IntegerType, false));
    }
    return listOfFields;
  }

  public static final int[] getPivotFieldRange(StructType schema, String pivotPrefix) {
    StructField[] schemaFields = schema.fields();
    int startAt = -1;
    int endAt = -1;
    for (int f=0; f < schemaFields.length; f++) {
      String name = schemaFields[f].name();
      if (startAt == -1 && name.startsWith(pivotPrefix)) {
        startAt = f;
      }
      if (startAt != -1 && !name.startsWith(pivotPrefix)) {
        endAt = f-1; // we saw the last field in the range before this field
        break;
      }
    }
    return new int[]{startAt,endAt};
  }

  public static final void fillPivotFieldValues(String rawValue, Object[] row, StructType schema, String pivotPrefix) {
    int[] range = getPivotFieldRange(schema, pivotPrefix);
    for (int i=range[0]; i <= range[1]; i++) row[i] = 0;
    try {
      row[schema.fieldIndex(pivotPrefix+rawValue.toLowerCase())] = 1;
    } catch (IllegalArgumentException ia) {
      row[range[1]] = 1;
    }
  }
}
