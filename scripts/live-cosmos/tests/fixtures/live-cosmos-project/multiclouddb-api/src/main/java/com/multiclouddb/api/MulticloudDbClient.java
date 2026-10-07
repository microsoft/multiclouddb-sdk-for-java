package com.multiclouddb.api;
public interface MulticloudDbClient extends AutoCloseable {
 void create(ResourceAddress a,MulticloudDbKey k,java.util.Map<String,Object>d); DocumentResult read(ResourceAddress a,MulticloudDbKey k); void delete(ResourceAddress a,MulticloudDbKey k); void close();
}
