package com.xxl.job.core.executor.impl;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.glue.GlueFactory;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * xxl-job executor (for spring)
 *
 * @author xuxueli 2018-11-01 09:24:52
 */
public class XxlJobSpringExecutor extends XxlJobExecutor implements ApplicationContextAware, SmartInitializingSingleton, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobSpringExecutor.class);

    /**
     * 所有的单例 Bean 创建完成后会回调该方法
     */
    @Override
    public void afterSingletonsInstantiated() {

        // init JobHandler Repository
        /*initJobHandlerRepository(applicationContext);*/

        /**
         * init JobHandler Repository (for method)
         *
         * 获取所有加了{@link XxlJob}注解的方法，并将其注册为{@link MethodJobHandler}
         */
        initJobHandlerMethodRepository(applicationContext);

        // refresh GlueFactory
        GlueFactory.refreshInstance(1);

        // super start
        try {
            /**
             *
             */
            super.start();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // destroy
    @Override
    public void destroy() {
        super.destroy();
    }


    /*private void initJobHandlerRepository(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return;
        }

        // init job handler action
        Map<String, Object> serviceBeanMap = applicationContext.getBeansWithAnnotation(JobHandler.class);

        if (serviceBeanMap != null && serviceBeanMap.size() > 0) {
            for (Object serviceBean : serviceBeanMap.values()) {
                if (serviceBean instanceof IJobHandler) {
                    String name = serviceBean.getClass().getAnnotation(JobHandler.class).value();
                    IJobHandler handler = (IJobHandler) serviceBean;
                    if (loadJobHandler(name) != null) {
                        throw new RuntimeException("xxl-job jobhandler[" + name + "] naming conflicts.");
                    }
                    registerJobHandler(name, handler);
                }
            }
        }
    }*/

    private void initJobHandlerMethodRepository(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return;
        }
        /**
         * 获取容器中所有的 BeanName 集合
         *
         * init job handler from method
         */
        String[] beanDefinitionNames = applicationContext.getBeanNamesForType(Object.class, false, true);
        /**
         * 遍历 BeanName 集合
         */
        for (String beanDefinitionName : beanDefinitionNames) {
            // 从容器中获取对应的 Bean
            Object bean = applicationContext.getBean(beanDefinitionName);

            Map<Method, XxlJob> annotatedMethods = null;   // referred to ：org.springframework.context.event.EventListenerMethodProcessor.processBean
            try {
                /**
                 * 获取这个 Bean 所有带{@link XxlJob}注解的方法
                 */
                annotatedMethods = MethodIntrospector.selectMethods(
                        bean.getClass(),
                        (MethodIntrospector.MetadataLookup<XxlJob>) method -> AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class)
                );
            }
            catch (Throwable ex) {
                logger.error("xxl-job resolve method for job handler error for bean [" + beanDefinitionName + "].", ex);
            }
            // 没有则继续
            if (annotatedMethods == null || annotatedMethods.isEmpty()) {
                continue;
            }

            /**
             * 遍历所有带{@link XxlJob}注解的方法
             */
            for (Map.Entry<Method, XxlJob> methodXxlJobEntry : annotatedMethods.entrySet()) {
                // 方法
                Method method = methodXxlJobEntry.getKey();
                // 注解
                XxlJob xxlJob = methodXxlJobEntry.getValue();
                // 注解不存在则继续
                if (xxlJob == null) {
                    continue;
                }
                // 注解所标明的任务名称
                String name = xxlJob.value();
                // 名称不能为空
                if (name.trim().length() == 0) {
                    throw new RuntimeException("xxl-job method-jobhandler name invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                }
                // 名称不能重复
                if (loadJobHandler(name) != null) {
                    throw new RuntimeException("xxl-job jobhandler[" + name + "] naming conflicts.");
                }

                /**
                 * 方法入参数量必须为一个且是{@link String}类型
                 *
                 * execute method
                 */
                if (!(method.getParameterTypes().length == 1 && method.getParameterTypes()[0].isAssignableFrom(String.class))) {
                    throw new RuntimeException("xxl-job method-jobhandler param-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " + "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }
                /**
                 * 返回值必须是{@link ReturnT}
                 */
                if (!method.getReturnType().isAssignableFrom(ReturnT.class)) {
                    throw new RuntimeException("xxl-job method-jobhandler return-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " + "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }
                method.setAccessible(true);

                // init and destroy
                Method initMethod = null;
                Method destroyMethod = null;
                // 是否有初始化方法
                if (xxlJob.init().trim().length() > 0) {
                    try {
                        initMethod = bean.getClass().getDeclaredMethod(xxlJob.init());
                        initMethod.setAccessible(true);
                    }
                    catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler initMethod invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                    }
                }
                // 是否有销毁方法
                if (xxlJob.destroy().trim().length() > 0) {
                    try {
                        destroyMethod = bean.getClass().getDeclaredMethod(xxlJob.destroy());
                        destroyMethod.setAccessible(true);
                    }
                    catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler destroyMethod invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                    }
                }

                /**
                 * 将该方法注册为{@link MethodJobHandler}对象
                 *
                 * registry MethodJobHandler
                 */
                registerJobHandler(name, new MethodJobHandler(bean, method, initMethod, destroyMethod));
            }
        }
    }

    // ---------------------- applicationContext ----------------------
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        XxlJobSpringExecutor.applicationContext = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

}
