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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.util.Utf8;
import org.slf4j.Logger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.StringUtils;
import org.apache.nutch.fetcher.FetcherJob;
import org.apache.nutch.scoring.ScoreDatum;
import org.apache.nutch.scoring.ScoringFilterException;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.storage.Mark;
import org.apache.nutch.storage.TheData;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.TableUtil;
import org.apache.nutch.util.WebPageWritable;
import org.apache.gora.mapreduce.GoraReducer;

public class GetTheDataReducer
extends GoraReducer<String, WebPage, String, TheData> {

  public static final String CRAWLDB_ADDITIONS_ALLOWED = "db.update.additions.allowed";	
	
  public static final Logger LOG = DbUpdaterJob.LOG;

  private int retryMax;
  private boolean additionsAllowed;
  private int maxInterval;
  private FetchSchedule schedule;
  private ScoringFilters scoringFilters;
  private List<ScoreDatum> inlinkedScoreData = new ArrayList<ScoreDatum>();
  private int maxLinks;

  @Override
  protected void setup(Context context) throws IOException, InterruptedException {
    Configuration conf = context.getConfiguration();
    retryMax = conf.getInt("db.fetch.retry.max", 3);
    additionsAllowed = conf.getBoolean(CRAWLDB_ADDITIONS_ALLOWED, true);
    maxInterval = conf.getInt("db.fetch.interval.max", 0 );
    schedule = FetchScheduleFactory.getFetchSchedule(conf);
    scoringFilters = new ScoringFilters(conf);
    maxLinks = conf.getInt("db.update.max.inlinks", 10000);
  }
  @Override
  protected void reduce(String key, Iterable<WebPage> values,
      Context context) throws IOException, InterruptedException {
	  int i=0;
	  TheData td = new TheData();
	  StringBuffer keywords = new StringBuffer("");
	  StringBuffer description = new StringBuffer("");
	  for(WebPage wp:values){
		  i++;
		  td.putToUrls(new Utf8(wp.getBaseUrl().toString()), new Utf8());
		  td.putToEmails(new Utf8(wp.getBaseUrl().toString()), new Utf8());
		  if(wp.getKeywords()!=null&&!"".equals(wp.getKeywords())){
			  keywords.append(wp.getKeywords()+",");
		  }
		  if(wp.getDescription()!=null&&!"".equals(wp.getDescription())){
			  description.append(wp.getDescription()+",");
		  }
	  }
	  if(!keywords.toString().equals("")){
		  if(keywords.length()>4096){
			  keywords = new StringBuffer(keywords.toString().substring(0, 4095));
		    }
		  td.setKeywords(new Utf8(keywords.toString()));
	  }
	  
	  if(!description.toString().equals("")){
		  if(description.length()>4096){
			  description = new StringBuffer(description.toString().substring(0, 4095));
		    }
		  td.setDescription(new Utf8(description.toString()));
	  }
	  
	  if(key.indexOf("@")!=-1){
		  td.setFlag(new Utf8("email"));
	  }else{
		  td.setFlag(new Utf8("phone"));
	  }
	  context.write(key, td);
	  if(i>1){
		  System.out.println("==========================OK================");
	  }
    
  }

}

