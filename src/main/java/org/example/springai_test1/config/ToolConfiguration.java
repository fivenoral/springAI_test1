package org.example.springai_test1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具配置类
 * <p>
 * 动态发现所有工具类并统一配置，避免 ChatClientConfig 参数膨胀。
 * <p>
 * 使用 BeanFactory.getType() 先检查类元数据（不触发实例化），
 * 只对包含 @Tool 方法的 bean 调用 getBean() 实例化，
 * 从而避免触发 Service 等非工具 bean 的提前初始化，打破循环依赖。
 */
@Configuration
public class ToolConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ToolConfiguration.class);

    /**
     * 动态发现所有工具类并转换为工具对象数组
     */
    @Bean
    public Object[] allToolCallbacks(BeanFactory beanFactory) {
        List<Object> toolBeans = new ArrayList<>();

        if (beanFactory instanceof ListableBeanFactory lbf) {
            String[] beanNames = lbf.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                try {
                    Class<?> beanType = lbf.getType(beanName);
                    if (beanType != null && hasToolMethods(beanType)) {
                        Object bean = lbf.getBean(beanName);
                        toolBeans.add(bean);
                        logger.info("发现工具类: {}", beanType.getSimpleName());
                    }
                } catch (Exception e) {
                    // 忽略无法解析的 bean definition
                }
            }
        }

        logger.info("总共发现 {} 个工具类", toolBeans.size());
        return toolBeans.toArray();
    }

    /**
     * 检查类中是否有 @Tool 注解的方法
     */
    private boolean hasToolMethods(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}
