package com.multiclouddb.api;
public interface MulticloudDbClient extends AutoCloseable {
 OperationResponse<Void> create(ResourceAddress a,MulticloudDbKey k,java.util.Map<String,Object>d); OperationResponse<DocumentResult> read(ResourceAddress a,MulticloudDbKey k); OperationResponse<Void> delete(ResourceAddress a,MulticloudDbKey k); void close();
}
