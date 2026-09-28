package org.example.config;

import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量化模型配置：本地 ONNX 模型（bge-small-zh-v15），无需外部 API Key，被知识库索引复用。
 */
@Configuration
public class RagConfig {

    /**
     * 向量化模型：本地 ONNX 模型（bge-small-zh-v15），维度 512，无需外部 API Key。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }
}