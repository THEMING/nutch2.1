/*******************************************************************************
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
 ******************************************************************************/
package org.apache.nutch.crawl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.avro.util.Utf8;
import org.apache.gora.mapreduce.GoraMapper;
import org.apache.gora.mapreduce.GoraOutputFormat;
import org.apache.gora.mapreduce.GoraRecordWriter;
import org.apache.gora.mapreduce.GoraReducer;
import org.apache.gora.query.Query;
import org.apache.gora.store.DataStore;
import org.apache.gora.store.DataStoreFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.UrlWithScore.UrlOnlyPartitioner;
import org.apache.nutch.crawl.UrlWithScore.UrlScoreComparator;
import org.apache.nutch.crawl.UrlWithScore.UrlScoreComparator.UrlOnlyComparator;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.ParseStatusCodes;
import org.apache.nutch.parse.ParseUtil;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.storage.Mark;
import org.apache.nutch.storage.ParseStatus;
import org.apache.nutch.storage.StorageUtils;
import org.apache.nutch.storage.TheData;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.EmailQueue;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.NutchTool;
import org.apache.nutch.util.TableUtil;
import org.apache.nutch.util.ToolUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetTheDataJob extends NutchTool implements Tool {

  public static final Logger LOG = LoggerFactory.getLogger(GetTheDataJob.class);


  private static final Collection<WebPage.Field> FIELDS =
    new HashSet<WebPage.Field>();
  
  static GoraRecordWriter grw;
  
  private static final Utf8 REPARSE = new Utf8("-reparse");
  
  private static final String RESUME_KEY = "parse.job.resume";
  private static final String FORCE_KEY = "parse.job.force";

  static {
	FIELDS.add(WebPage.Field.BASE_URL);
    FIELDS.add(WebPage.Field.OUTLINKS);
    FIELDS.add(WebPage.Field.INLINKS);
    FIELDS.add(WebPage.Field.STATUS);
    FIELDS.add(WebPage.Field.PREV_SIGNATURE);
    FIELDS.add(WebPage.Field.SIGNATURE);
    FIELDS.add(WebPage.Field.MARKERS);
    FIELDS.add(WebPage.Field.CONTENT);
    FIELDS.add(WebPage.Field.METADATA);
    FIELDS.add(WebPage.Field.RETRIES_SINCE_FETCH);
    FIELDS.add(WebPage.Field.FETCH_TIME);
    FIELDS.add(WebPage.Field.MODIFIED_TIME);
    FIELDS.add(WebPage.Field.FETCH_INTERVAL);
    FIELDS.add(WebPage.Field.PREV_FETCH_TIME);
    FIELDS.add(WebPage.Field.KEYWORDS);
    FIELDS.add(WebPage.Field.DESCRIPTION);
  }

  public static final Utf8 DISTANCE = new Utf8("dist");

  public GetTheDataJob() {

  }

  public GetTheDataJob(Configuration conf) {
    setConf(conf);
  }
  
  @Override
  public Map<String,Object> run(Map<String,Object> args) throws Exception {System.out.println("================getTheData===========================");
    String crawlId = (String)args.get(Nutch.ARG_CRAWL);
    numJobs = 1;
    currentJobNum = 0;
    currentJob = new NutchJob(getConf(), "getTheData");
    if (crawlId != null) {
      currentJob.getConfiguration().set(Nutch.CRAWL_ID_KEY, crawlId);
    }
    //job.setBoolean(ALL, updateAll);
    ScoringFilters scoringFilters = new ScoringFilters(getConf());
    HashSet<WebPage.Field> fields = new HashSet<WebPage.Field>(FIELDS);
    fields.addAll(scoringFilters.getFields());
    
    // Partition by {url}, sort by {url,score} and group by {url}.
    // This ensures that the inlinks are sorted by score when they enter
    // the reducer.
    
    currentJob.setPartitionerClass(UrlOnlyOnlyPartitioner.class);
    currentJob.setSortComparatorClass(UrlScoreComparator.class);
    currentJob.setGroupingComparatorClass(UrlOnlyComparator.class);
    
//    StorageUtils.initMapperJob(currentJob, fields, String.class, WebPage.class, GetTheDataMapper.class);
    
    DataStore<String, WebPage> store = StorageUtils.createWebStore(currentJob.getConfiguration(),
            String.class, WebPage.class);
        if (store==null) throw new RuntimeException("Could not create datastore");
        Query<String, WebPage> query = store.newQuery();
        query.setFields(StorageUtils.toStringArray(fields));
        GoraMapper.initMapperJob(currentJob, query, store,
        		String.class, WebPage.class, GetTheDataMapper.class, null, true);
        DataStore<String, TheData> store2 = StorageUtils.createWebStore(currentJob.getConfiguration(),
                String.class, TheData.class);
        GoraOutputFormat.setOutput(currentJob, store2, true);
    
    
    
    
//    StorageUtils.initReducerJob(currentJob, DbUpdateReducer.class);
    
    Configuration conf = currentJob.getConfiguration();
    DataStore<String, TheData> store3 =
      StorageUtils.createWebStore(conf, String.class, TheData.class);
    GoraReducer.initReducerJob(currentJob, store3, GetTheDataReducer.class);
    GoraOutputFormat.setOutput(currentJob, store3, true);
    
    
    
//    DataStore<String, WebPage> store4 = StorageUtils.createWebStore(currentJob.getConfiguration(),
//            String.class, WebPage.class);
//    Configuration conff = new Configuration();
//    conff.setInt("gora.buffer.write.limit", 1);
//	TaskAttemptContext context = new TaskAttemptContext(conff, new TaskAttemptID());
//	grw = new GoraRecordWriter(store4, context);
    
    
    
    
    
    
    
    
    currentJob.waitForCompletion(true);
    ToolUtil.recordJobStatus(null, currentJob, results);
    return results;
  }
  
  public static final class UrlOnlyOnlyPartitioner extends
	  Partitioner<String, WebPage> {
	@Override
	public int getPartition(String key, WebPage val, int reduces) {
	  return (key.hashCode() & Integer.MAX_VALUE) % reduces;
	}
	}
  
  public static class GetTheDataMapper 
	  extends GoraMapper<String, WebPage, String, WebPage> {
	private ParseUtil parseUtil;
	
	private boolean shouldResume;
	
	private boolean force;
	
	private Utf8 batchId;
	
	public static final String MY_GET_DATA_URL_NUM = "my.get.data.url.num";
	
	private int getDataNum = 10;
	  private static int ii=0;
	
	@Override
	public void setup(Context context) throws IOException {
	  Configuration conf = context.getConfiguration();
	  parseUtil = new ParseUtil(conf);
	  shouldResume = conf.getBoolean(RESUME_KEY, false);
	  force = conf.getBoolean(FORCE_KEY, false);
	  batchId = new Utf8(conf.get(GeneratorJob.BATCH_ID, Nutch.ALL_BATCH_ID_STR));
	  getDataNum = conf.getInt(MY_GET_DATA_URL_NUM, 10);
	  
	  Configuration conff = new Configuration();
	    conff.setInt("gora.buffer.write.limit", 1);
	  DataStore<String, WebPage> store4;
	try
	{
		store4 = StorageUtils.createWebStore(conff,
		            String.class, WebPage.class);
		TaskAttemptContext contextt = new TaskAttemptContext(conff, new TaskAttemptID());
		grw = new GoraRecordWriter(store4, contextt);
	}
	catch (ClassNotFoundException e)
	{
		e.printStackTrace();
	}
		
	}
	
	protected void cleanup(Context context) throws IOException ,InterruptedException {
	  	ii=0;
	  }
	
//	private int i=0;
	@Override
	public void map(String key, WebPage page, Context context)
	    throws IOException, InterruptedException {//System.out.println("==============getTheData================"+(i++));
		
		if(ii>getDataNum){
			return;
		}
		
	  Utf8 mark = Mark.PARSE_MARK.checkMark(page);
	  String unreverseKey = TableUtil.unreverseUrl(key);
	  if (batchId.equals(REPARSE)) {
	    LOG.debug("Reparsing " + unreverseKey);
	  } else {
	    if (!NutchJob.shouldProcess(mark, batchId)) {
	      LOG.info("Skipping " + TableUtil.unreverseUrl(key) + "; different batch id (" + mark + ")");
	      return;
	    }
	    if (shouldResume && Mark.PARSE_MARK.checkMark(page) == null && Mark.GET_THE_DATA.checkMark(page) != null) {
	      if (force) {
	        LOG.info("Forced parsing " + unreverseKey + "; already parsed");
	      } else {
	        LOG.info("Skipping " + unreverseKey + "; already parsed");
	        return;
	      }
	    } else {
	      LOG.info("Parsing " + unreverseKey);
	    }
	  }
	
	  List emails = getEmails(page);
//	  TheData td = new TheData();
	  if(emails.size()!=0){
		  for(int i=0;i<emails.size();i++){
			  TheData td = new TheData();
			  td.putToEmails(new Utf8(emails.get(i).toString()), new Utf8());
			  context.write(emails.get(i).toString(),page);
		  }
//		  context.write(page.getBaseUrl().toString(),td);
		  
	  }
	  
	  Utf8 parseMark = Mark.PARSE_MARK.checkMark(page);
      if (parseMark != null) {
        Mark.GET_THE_DATA.putMark(page, parseMark);
        grw.write(key,page);
      }
	  
      ii++;
	}    
	}
  
  private int getTheData(String crawlId) throws Exception {
    LOG.info("GetTheDataJob: starting");
    run(ToolUtil.toArgMap(Nutch.ARG_CRAWL, crawlId));
    LOG.info("GetTheDataJob: done");
    return 0;
  }

	  public static List getEmails(WebPage page)
	{
		  List li = new ArrayList();
		  String str = new String(page.getContent().array());
		  String regex="([a-zA-Z0-9_\\-\\.])+@[a-zA-Z0-9]+(?:[-.][a-zA-Z0-9]+)*\\.[a-zA-Z]+\\s*";
          Pattern pattern=Pattern.compile(regex);
          if(str!=null&&!"".equals(str)){
          	Matcher matcher=pattern.matcher(str);
              while(matcher.find()){
            	  String email = matcher.group();
//                  if(EmailQueue.isOK(email)){
//                	  EmailQueue.addEmails(email);
            	  if(!li.contains(email)){
            		  li.add(email);
            	  }
                	  
//                  }
              }
          }
          
          String phoneregex="((13[0-9])|(15[^4,\\D])|(18[0,5-9]))\\d{8}";//"^((13[0-9])|(15[^4,\\D])|(18[0,5-9]))\\d{8}$";
          
          Pattern phonepattern=Pattern.compile(phoneregex);
          if(str!=null&&!"".equals(str)){
          	Matcher matcher=phonepattern.matcher(str);
              while(matcher.find()){
            	  String phone = matcher.group();
//                  if(EmailQueue.isOK(email)){
//                	  EmailQueue.addEmails(email);
            	  if(!li.contains(phone)){
            		  li.add(phone);
            	  }
                	  
//                  }
              }
          }
		return li;
	}

@Override
public int run(String[] args) throws Exception {
	
    String crawlId = null;
    if (args.length == 0) {
      //
    } else if (args.length == 2 && "-crawlId".equals(args[0])) {
      crawlId = args[1];
    } else {
      throw new IllegalArgumentException("usage: " + "(-crawlId <id>)");
    }
    return getTheData(crawlId);
  }

  public static void main(String[] args) throws Exception {
	  while(true){
		  int res = ToolRunner.run(NutchConfiguration.create(), new GetTheDataJob(), args);
	  }
    
//    System.exit(res);
  }

}

