package org.example.springai_test1.service.impl;

import org.example.springai_test1.dto.DocumentUploadResult;
import org.example.springai_test1.rag.Bm25IndexService;
import org.example.springai_test1.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档服务实现
 * <p>
 * 使用 Spring AI 的 {@link TextReader} 将上传的纯文本文件转换为 {@link Document}，
 * 并按需写入 {@link VectorStore}（底层为 Chroma）。
 * <p>
 * 通过构造器注入 VectorStore，保持与其它组件解耦；文件到 Resource 的适配
 * 逻辑封装在私有内部类中，不对外暴露。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentServiceImpl.class);

    /** 内容预览最大长度 */
    private static final int PREVIEW_MAX_LENGTH = 200;

    /** Chroma Cloud 免费租户最大记录数限制 */
    private static final int CHROMA_CLOUD_MAX_RECORDS = 300;

    @Autowired
    private VectorStore vectorStore;

    /** BM25 稀疏检索索引（Hybrid Search 的另一路），与向量库双写保持一致 */
    @Autowired
    private Bm25IndexService bm25IndexService;

    @Override
    public List<Document> convertToDocuments(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unknown.txt";

        // 将上传字节包装为带文件名的 Resource，供 TextReader 读取并记录来源元数据
        Resource resource = new NamedByteArrayResource(file.getBytes(), fileName);

        TextReader textReader = new TextReader(resource);
        textReader.setCharset(StandardCharsets.UTF_8);
        // 追加自定义元数据（会合并到每个 Document 的 metadata 中）
        textReader.getCustomMetadata().put("source", fileName);
        textReader.getCustomMetadata().put("uploadTime", Instant.now().toString());

        List<Document> documents = textReader.get();
        logger.info("文件 [{}] 通过 TextReader 转换为 {} 个 Document", fileName, documents.size());
        return documents;
    }

    @Override
    public DocumentUploadResult upload(MultipartFile file, boolean storeToVectorStore) throws IOException {
        List<Document> documents = convertToDocuments(file);

        // 动态调整chunk大小，确保分割后的文档数量不超过300条
        List<Document> splitDocuments = splitDocumentsWithinLimit(documents);
        logger.info("文档分割后产生 {} 个 Document", splitDocuments.size());

        if (storeToVectorStore && !splitDocuments.isEmpty()) {
            vectorStore.add(splitDocuments);
            logger.info("已将 {} 个 Document 写入向量库", splitDocuments.size());
            // 同步写入 BM25 稀疏索引，保证 Hybrid Search 两路召回内容一致
            bm25IndexService.index(splitDocuments);
            logger.info("已将 {} 个 Document 写入 BM25 索引", splitDocuments.size());
        }

        List<String> documentIds = splitDocuments.stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        return new DocumentUploadResult(
                file.getOriginalFilename(),
                file.getSize(),
                splitDocuments.size(),
                storeToVectorStore && !splitDocuments.isEmpty(),
                documentIds,
                buildPreview(splitDocuments)
        );
    }

    /**
     * 动态调整chunk大小，确保分割后的文档数量不超过Chroma Cloud限制
     * 
     * @param documents 原始文档列表
     * @return 分割后的文档列表，数量不超过 CHROMA_CLOUD_MAX_RECORDS
     */
    private List<Document> splitDocumentsWithinLimit(List<Document> documents) {
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> splitDocs = splitter.apply(documents);
        
        if (splitDocs.size() <= CHROMA_CLOUD_MAX_RECORDS) {
            logger.info("使用默认配置成功分割文档，产生 {} 个Document", splitDocs.size());
            return splitDocs;
        }
        
        // 如果超过限制，需要手动合并文档以减少数量
        logger.warn("分割产生 {} 个Document，超过限制 {}，尝试合并文档",
                splitDocs.size(), CHROMA_CLOUD_MAX_RECORDS);
        return mergeDocumentsToLimit(splitDocs);
    }

    /**
     * 合并文档以满足数量限制
     */
    private List<Document> mergeDocumentsToLimit(List<Document> documents) {
        List<Document> merged = new java.util.ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        int targetCount = (int) Math.ceil(documents.size() / (double) CHROMA_CLOUD_MAX_RECORDS);
        
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            currentContent.append(doc.getText());
            
            if ((i + 1) % targetCount == 0 || i == documents.size() - 1) {
                Document mergedDoc = new Document(currentContent.toString(), doc.getMetadata());
                merged.add(mergedDoc);
                currentContent = new StringBuilder();
            }
        }
        
        logger.info("合并后文档数量: {}", merged.size());
        return merged.subList(0, Math.min(merged.size(), CHROMA_CLOUD_MAX_RECORDS));
    }

    /**
     * 构建首个 Document 的内容预览（超长截断）
     */
    private String buildPreview(List<Document> documents) {
        if (documents.isEmpty()) {
            return "";
        }
        String text = documents.get(0).getText();
        if (text == null) {
            return "";
        }
        return text.length() > PREVIEW_MAX_LENGTH
                ? text.substring(0, PREVIEW_MAX_LENGTH) + "..."
                : text;
    }

    /**
     * 带文件名的 ByteArrayResource
     * <p>
     * TextReader 会用 Resource 的文件名作为来源标识写入 metadata，
     * 原生 ByteArrayResource 的 getFilename() 返回 null，故在此覆写。
     */
    private static class NamedByteArrayResource extends ByteArrayResource {

        private final String fileName;

        NamedByteArrayResource(byte[] byteArray, String fileName) {
            super(byteArray);
            this.fileName = fileName;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }
}
