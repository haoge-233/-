package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        // 只取文件名部分：客户端可以在 multipart 的 filename 里塞路径（如 ../../x.md），
        // 直接 resolve 再 normalize 会把文件写到 uploads 目录之外
        String safeFilename = Paths.get(originalFilename).getFileName().toString();
        if (safeFilename.isEmpty() || safeFilename.contains("..")) {
            return ResponseEntity.badRequest().body("非法的文件名");
        }

        String fileExtension = getFileExtension(safeFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 使用原始文件名，而不是UUID，以便实现基于文件名的去重
            Path filePath = uploadDir.resolve(safeFilename).normalize();
            if (!filePath.startsWith(uploadDir)) {
                return ResponseEntity.badRequest().body("非法的文件路径");
            }
            
            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }
            
            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            FileUploadRes response = new FileUploadRes(
                    safeFilename,
                    fileUploadConfig.getPath() + "/" + safeFilename,
                    file.getSize()
            );

            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setData(response);

            // 文件上传成功后，自动调用向量索引服务
            String indexFailure;
            try {
                logger.info("开始为上传文件创建向量索引: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("向量索引创建成功: {}", filePath);
                indexFailure = null;
            } catch (Exception e) {
                logger.error("向量索引创建失败: {}, 错误: {}", filePath, e.getMessage(), e);
                indexFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            if (indexFailure != null) {
                // 此前这里吞掉异常仍返回 200：调用方以为文档已可检索，实际查不到，
                // 属于静默丢数据。文件确实已落盘，所以把状态如实返回并给出失败原因。
                response.setIndexed(false);
                response.setIndexError(indexFailure);
                apiResponse.setCode(500);
                apiResponse.setMessage("文件已保存到 " + response.getFilePath()
                        + "，但向量化失败，知识库检索不到它。请修复后重新上传。");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(apiResponse);
            }

            response.setIndexed(true);
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            return ResponseEntity.ok(apiResponse);

        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
