package org.example.constant;

public class MilvusConstants {
    
    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";
    
    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";
    
    /**
     * 向量维度，必须与 dashscope.embedding.model 的输出维度一致。
     * 当前 text-embedding-v4 输出 1024 维；换用其他维度的模型时，
     * 需同步改此常量并重建 collection 后重新向量化全部文档。
     */
    public static final int VECTOR_DIM = 1024;
    
    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;
    
    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;
    
    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;
    
    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
