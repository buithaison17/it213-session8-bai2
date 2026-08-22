package com.example.bai2;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Configuration
public class ChunkingStrategyConfig {
    @Bean(name = "processTypeATokenSplitter")
    @Primary
    public TextSplitter processTypeATokenSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(100)
                .withKeepSeparator(true)
                .withMaxNumChunks(10000)
                .build();
    }

    @Bean(name = "policyTypeBHeaderSplitter")
    public TextSplitter policyTypeBHeaderSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(400)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(100)
                .withMaxNumChunks(10000)
                .build();
    }
}
