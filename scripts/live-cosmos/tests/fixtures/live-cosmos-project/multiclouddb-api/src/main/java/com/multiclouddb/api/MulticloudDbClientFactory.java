package com.multiclouddb.api;
import java.nio.file.*;import java.util.*;
public final class MulticloudDbClientFactory {
 public static MulticloudDbClient create(MulticloudDbClientConfig ignored){return new Client();}
 static final class Client implements MulticloudDbClient {
  public void create(ResourceAddress a,MulticloudDbKey k,Map<String,Object>d){}
  public DocumentResult read(ResourceAddress a,MulticloudDbKey k){if(Boolean.getBoolean("fixture.read.fail"))throw new IllegalStateException("synthetic read failure");return new DocumentResult(Map.of("id",k.partitionKey()));}
  public void delete(ResourceAddress a,MulticloudDbKey k){marker("delete");if(Boolean.getBoolean("fixture.delete.fail"))throw new IllegalStateException("synthetic delete failure");}
  public void close(){marker("close");if(Boolean.getBoolean("fixture.close.fail"))throw new IllegalStateException("synthetic close failure");}
  static void marker(String n){try{Path dir=Path.of(System.getProperty("fixture.marker.dir","target/fixture-markers"));Files.createDirectories(dir);Files.writeString(dir.resolve(n),"called");}catch(Exception e){throw new RuntimeException(e);}}
 }
}
