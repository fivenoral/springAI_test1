package org.example.springai_test1.service;

import org.example.springai_test1.dto.DocumentUploadResult;
import org.springframework.ai.document.Document;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文档服务接口
 * <p>
 * 负责将上传的文本文件通过 Spring AI 的 TextReader 转换为 {@link Document} 列表，
 * 并可选地写入向量库。面向接口设计，Controller 仅依赖本接口，实现类可自由替换。
 */
public interface DocumentService {

    /**
     * 将上传文件通过 TextReader 转换为 Document 列表（纯转换，不落库）。
     *
     * @param file 上传的文本文件
     * @return 转换得到的 Document 列表
     * @throws IOException 文件读取失败时抛出
     */
    List<Document> convertToDocuments(MultipartFile file) throws IOException;

    /**
     * 上传文件：转换为 Document 列表，并按需写入向量库，返回处理结果摘要。
     *
     * @param file                上传的文本文件
     * @param storeToVectorStore  是否将转换结果写入向量库
     * @return 处理结果摘要
     * @throws IOException 文件读取失败时抛出
     */
    DocumentUploadResult upload(MultipartFile file, boolean storeToVectorStore) throws IOException;
}
