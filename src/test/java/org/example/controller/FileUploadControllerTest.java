package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.service.VectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传接口的安全边界：文件名不得让文件写到 uploads 目录之外，扩展名必须受白名单约束
 */
class FileUploadControllerTest {

    @TempDir
    Path uploadDir;

    private MockMvc mockMvc;
    private VectorIndexService vectorIndexService;

    @BeforeEach
    void setUp() {
        FileUploadConfig config = new FileUploadConfig();
        config.setPath(uploadDir.toString());
        config.setAllowedExtensions("txt,md");

        vectorIndexService = mock(VectorIndexService.class);

        FileUploadController controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "fileUploadConfig", config);
        ReflectionTestUtils.setField(controller, "vectorIndexService", vectorIndexService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockMultipartFile file(String originalName, String content) {
        return new MockMultipartFile("file", originalName, "text/markdown", content.getBytes());
    }

    @Test
    void 正常文件名落在上传目录内() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("runbook.md", "# 内容")))
                .andExpect(status().isOk());

        assertThat(uploadDir.resolve("runbook.md")).exists();
    }

    @Test
    void 路径穿越文件名被剥离到只剩文件名() throws Exception {
        Path outside = uploadDir.getParent().resolve("evil.md");
        Files.deleteIfExists(outside);

        mockMvc.perform(multipart("/api/upload").file(file("../evil.md", "# 逃逸")))
                .andExpect(status().isOk());

        // 穿越部分必须被剥掉，文件只能落在 uploads 内
        assertThat(outside).doesNotExist();
        assertThat(uploadDir.resolve("evil.md")).exists();
    }

    @Test
    void 绝对路径文件名同样被剥离() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("/etc/passwd.md", "x")))
                .andExpect(status().isOk());

        assertThat(uploadDir.resolve("passwd.md")).exists();
    }

    @Test
    void 反斜杠路径穿越被剥离() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("..\\..\\win.md", "x")))
                .andExpect(status().isOk());

        assertThat(uploadDir.resolve("win.md")).exists();
    }

    @Test
    void windows绝对路径不会留下带反斜杠的文件名() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("C:\\Users\\victim\\Desktop\\note.md", "x")))
                .andExpect(status().isOk());

        // Linux 上反斜杠是合法文件名字符：不先归一化分隔符就会真的创建出这个怪文件，
        // 只有在 Windows 上才会碰巧被 Paths.get 剥掉
        assertThat(uploadDir.resolve("C:\\Users\\victim\\Desktop\\note.md")).doesNotExist();
        assertThat(uploadDir.resolve("note.md")).exists();
    }

    @Test
    void 白名单外扩展名被拒绝() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("payload.sh", "rm -rf /")))
                .andExpect(status().isBadRequest());

        assertThat(uploadDir.resolve("payload.sh")).doesNotExist();
    }

    @Test
    void 无扩展名文件被拒绝() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("noext", "x")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 空文件被拒绝() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(new MockMultipartFile("file", "a.md", "text/markdown", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 索引用的是净化后的路径() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file("../safe.md", "# ok")))
                .andExpect(status().isOk());

        verify(vectorIndexService).indexSingleFile(anyString());
        // 传给索引服务的字符串必须位于上传目录之下
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(vectorIndexService).indexSingleFile(captor.capture());
        assertThat(Path.of(captor.getValue()).normalize()).startsWith(uploadDir.toAbsolutePath().normalize());
    }

    @Test
    void 向量化失败时返回500并如实标注未入库() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("Milvus 连接超时"))
                .when(vectorIndexService).indexSingleFile(anyString());

        String body = mockMvc.perform(multipart("/api/upload").file(file("broken.md", "# 内容")))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("\"indexed\":false");
        assertThat(body).contains("Milvus 连接超时");
        assertThat(body).contains("检索不到");

        // 文件确实已落盘，这一点不能因为返回 500 而被否认
        assertThat(uploadDir.resolve("broken.md")).exists();
    }

    @Test
    void 向量化成功时标注已入库() throws Exception {
        String body = mockMvc.perform(multipart("/api/upload").file(file("good.md", "# 内容")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("\"indexed\":true");
    }
}
