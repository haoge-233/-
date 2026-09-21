package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档分片：分片长度受 maxSize 约束，序号连续，标题被保留
 */
class DocumentChunkServiceTest {

    private DocumentChunkService service;

    @BeforeEach
    void setUp() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(200);
        config.setOverlap(20);
        service = new DocumentChunkService();
        ReflectionTestUtils.setField(service, "chunkConfig", config);
    }

    @Test
    void 空内容返回空列表() {
        assertThat(service.chunkDocument("   ", "empty.md")).isEmpty();
        assertThat(service.chunkDocument(null, "null.md")).isEmpty();
    }

    @Test
    void 短文档只产出一个分片() {
        List<DocumentChunk> chunks = service.chunkDocument("# 标题\n很短的内容", "short.md");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getChunkIndex()).isZero();
    }

    @Test
    void 分片序号从0开始连续递增() {
        String longDoc = "# 章节\n" + "内容段落。".repeat(300);
        List<DocumentChunk> chunks = service.chunkDocument(longDoc, "long.md");

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
        }
    }

    @Test
    void 分片长度有界且不撞Milvus内容上限() {
        String longDoc = "# 章节\n" + "abcdefghij".repeat(200);
        List<DocumentChunk> chunks = service.chunkDocument(longDoc, "long.md");

        assertThat(chunks).isNotEmpty();
        // overlap 是额外附加的，因此实际上界是 maxSize + overlap + 段落分隔符，
        // 但必须远低于 Milvus content 字段的 8192 硬限制
        chunks.forEach(c -> assertThat(c.getContent().length())
                .isLessThanOrEqualTo(200 + 20 + 2));
    }

    @Test
    void 无空行的超长段落被硬切而非整段成为一片() {
        // 回归：原实现只按空行分段，一个不含空行的 2000 字段落会整体成为一个分片，
        // 既破坏检索粒度，也可能撞上 Milvus 8192 上限
        String oneGiantParagraph = "x".repeat(4000);
        List<DocumentChunk> chunks = service.chunkDocument("# 标题\n" + oneGiantParagraph, "log.md");

        assertThat(chunks).hasSizeGreaterThan(5);
        chunks.forEach(c -> assertThat(c.getContent().length()).isLessThan(1000));
    }

    @Test
    void 超长文档不会产出超过内容字段上限的分片() {
        String huge = "# 标题\n" + "y".repeat(60000);
        List<DocumentChunk> chunks = service.chunkDocument(huge, "huge.md");

        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> assertThat(c.getContent().length()).isLessThan(8192));
    }

    @Test
    void 按Markdown标题切分并保留标题() {
        String doc = """
                # 第一节
                第一节的内容，足够长一些用来验证切分。

                # 第二节
                第二节的内容，也是独立的一段描述文字。
                """;
        List<DocumentChunk> chunks = service.chunkDocument(doc, "sections.md");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).anyMatch(c -> "第一节".equals(c.getTitle()));
        assertThat(chunks).anyMatch(c -> "第二节".equals(c.getTitle()));
    }

    @Test
    void 分片位置索引单调不减() {
        String longDoc = "# 章节\n" + "x".repeat(1500);
        List<DocumentChunk> chunks = service.chunkDocument(longDoc, "long.md");

        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getStartIndex()).isGreaterThanOrEqualTo(chunks.get(i - 1).getStartIndex());
        }
    }
}
