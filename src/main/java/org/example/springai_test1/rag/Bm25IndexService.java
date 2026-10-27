package org.example.springai_test1.rag;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * BM25 稀疏检索索引服务（Hybrid Search 的稀疏通道）。
 * <p>
 * 基于 Apache Lucene 内嵌实现：在本地磁盘维护一个全文索引，与 Chroma 稠密向量库并行。
 * Lucene 的 {@link IndexSearcher} 默认打分器即 BM25（{@code BM25Similarity}），无需额外配置。
 * <p>
 * 索引结构：每个 {@link Document} 存两个字段——
 * <ul>
 *   <li>{@code id}：{@link StringField}，与向量库中的 Document id 一致，用于 RRF 融合时跨通道去重、回查；</li>
 *   <li>{@code content}：{@link TextField}，经 {@link SmartChineseAnalyzer} 中文分词后参与 BM25 打分。</li>
 * </ul>
 * 写入使用 {@code updateDocument(id, ...)}，同一 id 重复写入会覆盖而非产生重复记录。
 */
@Service
public class Bm25IndexService {

    private static final Logger logger = LoggerFactory.getLogger(Bm25IndexService.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";

    /** BM25 索引磁盘目录（相对路径，运行时解析为绝对路径） */
    @Value("${app.rag.bm25.index-dir:bm25-index}")
    private String indexDir;

    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    /** 读写锁：Lucene 同一目录只允许一个 IndexWriter，且读写不可并发，避免 Windows 文件句柄冲突。 */
    private final Object rwLock = new Object();

    @PostConstruct
    public void init() throws IOException {
        Path path = Paths.get(indexDir).toAbsolutePath();
        // 使用 NIOFSDirectory 而非默认的 MMapDirectory：
        // Windows 上 mmap 的文件句柄在 GC 前无法 rename/delete，会导致 commit 时 FileSystemException
        this.directory = new NIOFSDirectory(path);
        this.analyzer = new SmartChineseAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
        // SearcherManager 统一管理 reader 生命周期，避免每次 search 都 open/close reader
        this.searcherManager = new SearcherManager(writer, true, true, null);
        logger.info("BM25 索引目录初始化完成: {}", path);
    }

    @PreDestroy
    public void close() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
        } catch (IOException e) {
            logger.warn("关闭 SearcherManager 失败", e);
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            logger.warn("关闭 IndexWriter 失败", e);
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            logger.warn("关闭 Directory 失败", e);
        }
        if (analyzer != null) {
            analyzer.close();
        }
    }

    /**
     * 将文档写入（或按 id 覆盖更新）BM25 索引。
     *
     * @param documents 待索引文档；null/空列表将被忽略
     */
    public void index(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        synchronized (rwLock) {
            try {
                int count = 0;
                for (Document doc : documents) {
                    String text = doc.getText();
                    if (!StringUtils.hasText(text)) {
                        continue;
                    }
                    org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
                    luceneDoc.add(new StringField(FIELD_ID, doc.getId(), Field.Store.YES));
                    luceneDoc.add(new TextField(FIELD_CONTENT, text, Field.Store.YES));
                    // 按 id 覆盖，保证与向量库一致、避免重复
                    writer.updateDocument(new Term(FIELD_ID, doc.getId()), luceneDoc);
                    count++;
                }
                writer.commit();
                // 刷新 searcher，使新写入的文档对后续 search 可见
                searcherManager.maybeRefresh();
                logger.info("BM25 索引写入 {} 个 Document", count);
            } catch (IOException e) {
                logger.error("BM25 索引写入失败", e);
                throw new RuntimeException("BM25 索引写入失败", e);
            }
        }
    }

    /**
     * 用 BM25 检索 top-N 命中，结果按相关度（BM25 分数）降序。
     *
     * @param queryText 查询串
     * @param topN      返回条数上限
     * @return 命中列表；索引尚未建立或查询为空时返回空列表
     */
    public List<Bm25Hit> search(String queryText, int topN) {
        if (!StringUtils.hasText(queryText) || topN <= 0) {
            return List.of();
        }
        synchronized (rwLock) {
            // SearcherManager.acquire() 复用 reader，避免每次 search 都重新打开
            IndexSearcher searcher = null;
            try {
                searcher = searcherManager.acquire();
                QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
                // 转义 Lucene 语法保留字符，避免中文/符号混排导致解析异常
                Query query = parser.parse(QueryParser.escape(queryText));

                TopDocs topDocs = searcher.search(query, topN);
                List<Bm25Hit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document d = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(new Bm25Hit(d.get(FIELD_ID), d.get(FIELD_CONTENT), scoreDoc.score));
                }
                return hits;
            } catch (Exception e) {
                logger.warn("BM25 检索失败，降级为空结果: {}", e.getMessage());
                return List.of();
            } finally {
                if (searcher != null) {
                    try {
                        searcherManager.release(searcher);
                    } catch (IOException e) {
                        logger.warn("释放 IndexSearcher 失败", e);
                    }
                }
            }
        }
    }

    /**
     * BM25 单条命中结果。
     *
     * @param id      与向量库一致的 Document id
     * @param content 文档正文
     * @param score   BM25 相关度分数（仅用于同通道内排序，跨通道融合只看 rank）
     */
    public record Bm25Hit(String id, String content, float score) {
    }
}
