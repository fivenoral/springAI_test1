package org.example.springai_test1.dto;

import java.util.List;

/**
 * 文件上传并转换为 Document 的结果 DTO
 *
 * @param fileName             上传的文件名
 * @param fileSize             文件大小（字节）
 * @param documentCount        转换得到的 Document 数量
 * @param storedToVectorStore  是否已写入向量库
 * @param documentIds          转换得到的 Document ID 列表
 * @param contentPreview       首个 Document 的内容预览（截断）
 */
public record DocumentUploadResult(
        String fileName,
        long fileSize,
        int documentCount,
        boolean storedToVectorStore,
        List<String> documentIds,
        String contentPreview
) {
}
