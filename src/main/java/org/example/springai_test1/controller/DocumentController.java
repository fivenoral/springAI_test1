package org.example.springai_test1.controller;

import org.example.springai_test1.dto.DocumentUploadResult;
import org.example.springai_test1.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档控制器 —— REST API
 * <p>
 * 提供文件上传接口：将上传的文本文件通过 TextReader 转换为 Document 列表，
 * 并可选写入向量库。仅依赖 {@link DocumentService} 接口，与具体实现解耦。
 */
@RestController
@RequestMapping("/api/document")
public class DocumentController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文件并转换为 Document 列表
     * POST /api/document/upload
     * 参数：file（文件）、store（是否写入向量库，默认 false）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "store", defaultValue = "false") boolean store) {

        logger.info("API调用 - 文件上传: 文件名={}, 大小={}, 写入向量库={}",
                file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : 0, store);

        if (file == null || file.isEmpty()) {
            logger.warn("API调用失败 - 上传文件为空");
            return ResponseEntity.badRequest().body(Map.of("error", "上传文件不能为空"));
        }

        try {
            DocumentUploadResult result = documentService.upload(file, store);
            logger.info("API调用成功 - 文件上传完成: 文件名={}, 转换 Document 数={}",
                    result.fileName(), result.documentCount());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            logger.warn("API调用失败 - 文件上传参数错误: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("文件上传失败 - 文件名={}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "文件上传失败: " + e.getMessage()));
        }
    }
}
