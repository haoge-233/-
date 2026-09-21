package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {

    private String fileName;
    private String filePath;
    private Long fileSize;

    /** 是否已成功向量化入库。false 表示文件虽已落盘，但检索时查不到它 */
    private boolean indexed;

    /** 向量化失败原因，仅 indexed=false 时有值 */
    private String indexError;

    public FileUploadRes() {
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

}
