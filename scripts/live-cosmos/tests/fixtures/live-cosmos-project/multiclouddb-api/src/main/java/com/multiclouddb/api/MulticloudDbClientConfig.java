package com.multiclouddb.api;
public final class MulticloudDbClientConfig {
  public static Builder builder(){return new Builder();}
  public static final class Builder {
    public Builder provider(ProviderId ignored){return this;}
    public Builder connection(String name,String value){if(!"endpoint".equals(name)||value==null)throw new IllegalArgumentException();return this;}
    public MulticloudDbClientConfig build(){return new MulticloudDbClientConfig();}
  }
}
