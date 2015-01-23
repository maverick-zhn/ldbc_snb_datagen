/*
* Copyright (c) 2013 LDBC
* Linked Data Benchmark Council (http://ldbc.eu)
*
* This file is part of ldbc_socialnet_dbgen.
*
* ldbc_socialnet_dbgen is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* ldbc_socialnet_dbgen is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with ldbc_socialnet_dbgen.  If not, see <http://www.gnu.org/licenses/>.
*
* Copyright (C) 2011 OpenLink Software <bdsmt@openlinksw.com>
* All Rights Reserved.
*
* This program is free software; you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation;  only Version 2 of the License dated
* June 1991.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package ldbc.snb.datagen.serializer;

import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.generator.DatagenParams;
import ldbc.snb.datagen.objects.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.DefaultCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Properties;

/**
 * Created by aprat on 3/27/14.
 */
public class UpdateEventSerializer {
	
	private class UpdateStreamStats {
		public long minDate_ = Long.MAX_VALUE;
		public long maxDate_ = Long.MIN_VALUE;
		public long count_ = 0;
	}
	
	private SequenceFile.Writer streamWriter_[];
	private ArrayList<String> data_;
	private ArrayList<String> list_;
	private UpdateEvent currentEvent_;
	private int numPartitions_ = 1;
	private int nextPartition_ = 0;
	private StringBuffer stringBuffer_;
	private long currentDependantDate_ = 0;
	private Configuration conf_;
	private UpdateStreamStats  stats_;
	private String fileNamePrefix_;
	
	public UpdateEventSerializer(Configuration conf, String fileNamePrefix, int numPartitions ) {
		conf_ = conf;
		stringBuffer_ = new StringBuffer(512);
		data_ = new ArrayList<String>();
		list_ = new ArrayList<String>();
		currentEvent_ = new UpdateEvent(-1,-1, UpdateEvent.UpdateEventType.NO_EVENT,new String(""));
		numPartitions_ = numPartitions;
		stats_ = new UpdateStreamStats();
		fileNamePrefix_ = fileNamePrefix;
		try{
			streamWriter_ = new SequenceFile.Writer[numPartitions_];
			FileContext fc = FileContext.getFileContext(conf);
			for( int i = 0; i < numPartitions_; ++i ) {
				Path outFile = new Path(fileNamePrefix_+"_"+i);
				streamWriter_[i] = SequenceFile.createWriter(fc, conf, outFile, LongWritable.class, Text.class, CompressionType.NONE, new DefaultCodec(),new SequenceFile.Metadata(), EnumSet.of(CreateFlag.CREATE), Options.CreateOpts.checksumParam(Options.ChecksumOpt.createDisabled()));
				FileSystem fs = FileSystem.get(conf);
				Path propertiesFile = new Path(fileNamePrefix+".properties");
				if(fs.exists(propertiesFile)){
					FSDataInputStream file = fs.open(propertiesFile);
					Properties properties = new Properties();
					properties.load(file);
					stats_.minDate_ = Long.parseLong(properties.getProperty("ldbc.snb.interactive.min_write_event_start_time"));
					stats_.maxDate_ = Long.parseLong(properties.getProperty("ldbc.snb.interactive.max_write_event_start_time"));
					stats_.count_ = Long.parseLong(properties.getProperty("ldbc.snb.interactive.num_events"));
					file.close();
                    fs.delete(propertiesFile,true);
				}
			}
		} catch(IOException e){
			System.err.println(e.getMessage());
			System.exit(-1);
		}
	}
	
	public void changePartition() {
		nextPartition_ = (++nextPartition_) % numPartitions_;
	}
	
	public void writeKeyValue( UpdateEvent event ) {
		try{
			StringBuffer string = new StringBuffer();
			string.append(Long.toString(event.date));
			string.append("|");
			string.append(Long.toString(event.dependantDate));
			string.append("|");
			string.append(Integer.toString(event.type.ordinal()+1));
			string.append("|");
			string.append(event.eventData);
			string.append("\n");
			streamWriter_[nextPartition_].append(new LongWritable(event.date),new Text(string.toString()));
		} catch(IOException e){
			System.err.println(e.getMessage());
			System.exit(-1);
		}
	}
	
	private String formatStringArray(ArrayList<String> array, String separator) {
		if( array.size() == 0 ) return "";
		stringBuffer_.setLength(0);
		for( String s : array) {
			stringBuffer_.append(s);
			stringBuffer_.append(separator);
		}
		return stringBuffer_.substring(0,stringBuffer_.length()-1);
	}
	
	private void beginEvent( long date, UpdateEvent.UpdateEventType type ) {
		stats_.minDate_ = stats_.minDate_ > date ? date : stats_.minDate_;
		stats_.maxDate_ = stats_.maxDate_ < date ? date : stats_.maxDate_;
		stats_.count_++;
		currentEvent_.date = date;
		currentEvent_.dependantDate = currentDependantDate_;
		currentEvent_.type = type;
		currentEvent_.eventData = null;
		data_.clear();
	}
	
	private void endEvent() {
		currentEvent_.eventData = formatStringArray(data_,"|");
		writeKeyValue(currentEvent_);
        changePartition();
	}
	
	private void beginList() {
		list_.clear();
	}
	
	private void endList() {
		data_.add(formatStringArray(list_,";"));
	}
	
	
	public void close() {
		try {
			FileSystem fs = FileSystem.get(conf_);
			for( int i = 0; i < numPartitions_; ++i ) {
				streamWriter_[i].close();
			}
			
			if(DatagenParams.updateStreams) {
				OutputStream output = fs.create(new Path(fileNamePrefix_+".properties"),true);
				output.write(new String("ldbc.snb.interactive.gct_delta_duration:" + DatagenParams.deltaTime + "\n").getBytes());
				output.write(new String("ldbc.snb.interactive.min_write_event_start_time:" + stats_.minDate_ + "\n").getBytes());
				output.write(new String("ldbc.snb.interactive.max_write_event_start_time:" + stats_.maxDate_ + "\n").getBytes());
                if( stats_.count_ != 0 )  {
                    output.write(new String("ldbc.snb.interactive.update_interleave:" + (stats_.maxDate_ - stats_.minDate_) / stats_.count_ + "\n").getBytes());
                } else {
                    output.write(new String("ldbc.snb.interactive.update_interleave:" + "0" + "\n").getBytes());
                }
				output.write(new String("ldbc.snb.interactive.num_events:" + stats_.count_).getBytes());
				output.close();
			}
		} catch(IOException e){
			System.err.println(e.getMessage());
			System.exit(-1);
		}
	}
	
	public void export(Person person) {
		
		currentDependantDate_ = 0;
		beginEvent(person.creationDate(), UpdateEvent.UpdateEventType.ADD_PERSON);
		data_.add(Long.toString(person.accountId()));
		data_.add(person.firstName());
		data_.add(person.lastName());
		
		if(person.gender() == 1) {
			data_.add("male");
		} else {
			data_.add("female");
		}
		data_.add(Dictionaries.dates.formatDate(person.birthDay()));
		data_.add(Long.toString(person.creationDate()));
		data_.add(person.ipAddress().toString());
		data_.add(Dictionaries.browsers.getName(person.browserId()));
		data_.add(Integer.toString(person.cityId()));
		
		beginList();
		for( Integer l : person.languages()) {
			list_.add(Dictionaries.languages.getLanguageName(l));
		}
		endList();
		
		beginList();
		for(String e : person.emails()) {
			list_.add(e);
		}
		endList();
		
		beginList();
		for(Integer tag : person.interests()) {
			list_.add(Integer.toString(tag));
		}
		endList();
		
		beginList();
		long universityId = person.universityLocationId();
		if ( universityId != -1){
			if (person.classYear() != -1 ) {
				ArrayList<String> studyAtData = new ArrayList<String>();
				studyAtData.add(Long.toString(universityId));
				studyAtData.add(Dictionaries.dates.formatYear(person.classYear()));
				list_.add(formatStringArray(studyAtData,","));
			}
		}
		endList();
		
		beginList();
		for( Long companyId : person.companies().keySet()) {
			ArrayList<String> workAtData = new ArrayList<String>();
			workAtData.add(Long.toString(companyId));
			workAtData.add(Dictionaries.dates.formatYear(person.companies().get(companyId)));
			list_.add(formatStringArray(workAtData,","));
		}
		endList();
		endEvent();
	}
	
	public void export(Knows k) {
		currentDependantDate_ = Math.max(k.from().creationDate(), k.to().creationDate());
		beginEvent(k.creationDate(), UpdateEvent.UpdateEventType.ADD_FRIENDSHIP);
		data_.add(Long.toString(k.from().accountId()));
		data_.add(Long.toString(k.to().accountId()));
		data_.add(Long.toString(k.creationDate()));
		endEvent();
	}
	
	public void export(Post post) {
		currentDependantDate_ = post.author().creationDate();
		beginEvent(post.creationDate(), UpdateEvent.UpdateEventType.ADD_POST);
		String empty = "";
		data_.add(Long.toString(post.messageId()));
		data_.add(empty);
		data_.add(Long.toString(post.creationDate()));
		data_.add(post.ipAddress().toString());
		data_.add(Dictionaries.browsers.getName(post.browserId()));
		data_.add(Dictionaries.languages.getLanguageName(post.language()));
		data_.add(post.content());
		data_.add(Long.toString(post.content().length()));
		data_.add(Long.toString(post.author().accountId()));
		data_.add(Long.toString(post.forumId()));
		data_.add(Long.toString(Dictionaries.ips.getLocation(post.ipAddress())));
		
		beginList();
		for( int tag : post.tags()) {
			list_.add(Integer.toString(tag));
		}
		endList();
		endEvent();
	}
	
	public void export(Like like) {
		currentDependantDate_ = like.userCreationDate;
		if( like.type == Like.LikeType.COMMENT) {
			beginEvent(like.date, UpdateEvent.UpdateEventType.ADD_LIKE_COMMENT);
		} else {
			beginEvent(like.date, UpdateEvent.UpdateEventType.ADD_LIKE_POST);
		}
		data_.add(Long.toString(like.user));
		data_.add(Long.toString(like.messageId));
		data_.add(Long.toString(like.date));
		endEvent();
	}
	
	public void export(Photo photo) {
		
		currentDependantDate_ = photo.author().creationDate();
		beginEvent(photo.creationDate(), UpdateEvent.UpdateEventType.ADD_POST);
		String empty = "";
		data_.add(Long.toString(photo.messageId()));
		data_.add(photo.content());
		data_.add(Long.toString(photo.creationDate()));
		data_.add(photo.ipAddress().toString());
		data_.add(Dictionaries.browsers.getName(photo.browserId()));
		data_.add(empty);
		data_.add(empty);
		data_.add("0");
		data_.add(Long.toString(photo.author().accountId()));
		data_.add(Long.toString(photo.forumId()));
		data_.add(Long.toString(Dictionaries.ips.getLocation(photo.ipAddress())));
		
		beginList();
		for( int tag : photo.tags()) {
			list_.add(Integer.toString(tag));
		}
		endList();
		endEvent();
	}
	
	public void export(Comment comment) {
		
		currentDependantDate_ = comment.author().creationDate();
		beginEvent(comment.creationDate(), UpdateEvent.UpdateEventType.ADD_COMMENT);
		data_.add(Long.toString(comment.messageId()));
		data_.add(Long.toString(comment.creationDate()));
		data_.add(comment.ipAddress().toString());
		data_.add(Dictionaries.browsers.getName(comment.browserId()));
		data_.add(comment.content());
		data_.add(Integer.toString(comment.content().length()));
		data_.add(Long.toString(comment.author().accountId()));
		data_.add(Long.toString(Dictionaries.ips.getLocation(comment.ipAddress())));
		if (comment.replyOf() == comment.postId()) {
			data_.add(Long.toString(comment.postId()));
			data_.add("-1");
		} else {
			data_.add("-1");
			data_.add(Long.toString(comment.replyOf()));
		}
		beginList();
		for( int tag : comment.tags()) {
			list_.add(Integer.toString(tag));
		}
		endList();
		endEvent();
	}
	
	public void export(Forum forum) {
		currentDependantDate_ = forum.moderator().creationDate();
		beginEvent(forum.creationDate(), UpdateEvent.UpdateEventType.ADD_FORUM);
		data_.add(Long.toString(forum.id()));
		data_.add(forum.title());
		data_.add(Long.toString(forum.creationDate()));
		data_.add(Long.toString(forum.moderator().accountId()));
		
		beginList();
		for( int tag : forum.tags()) {
			list_.add(Integer.toString(tag));
		}
		endList();
		endEvent();
	}
	
	public void export(ForumMembership membership) {
		currentDependantDate_ = membership.person().creationDate();
		beginEvent(membership.creationDate(), UpdateEvent.UpdateEventType.ADD_FORUM_MEMBERSHIP);
		data_.add(Long.toString(membership.forumId()));
		data_.add(Long.toString(membership.person().accountId()));
		data_.add(Long.toString(membership.creationDate()));
		endEvent();
	}
	
}
