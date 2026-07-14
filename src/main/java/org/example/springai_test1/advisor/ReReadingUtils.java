package org.example.springai_test1.advisor;

/**
 * 重读工具类 - 让模型多次阅读输入以提升推理能力
 * 通过在用户消息前添加重读提示，引导模型进行多次思考
 * 由于Advisor接口依赖问题，改为在业务层使用此工具类
 */
public class ReReadingUtils {

    private static final String RE_READING_PROMPT = """
            请仔细阅读以下内容，理解其中的含义和逻辑关系：
            
            {user_content}
            
            请重新阅读上述内容，思考是否有遗漏的信息或需要深入分析的地方：
            
            {user_content}
            
            基于你的理解，请回答用户的问题或完成相应的任务。
            """;

    /**
     * 增强用户消息，添加重读提示
     */
    public static String enhanceUserMessage(String originalContent) {
        return RE_READING_PROMPT.replace("{user_content}", originalContent);
    }
}
