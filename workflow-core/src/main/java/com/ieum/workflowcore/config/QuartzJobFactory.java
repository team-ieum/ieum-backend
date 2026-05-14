package com.ieum.workflowcore.config;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz Job 인스턴스에 Spring 의존성을 주입하는 팩토리.
 *
 * <p>Quartz는 Job 객체를 직접 생성하므로 기본적으로 Spring Bean이 아니다.
 * 이 팩토리가 {@link AutowireCapableBeanFactory}를 통해 {@code @Autowired} 필드를 주입한다.
 * ApplicationContext는 {@link SchedulerConfig}에서 생성 시점에 주입된다.
 */
public class QuartzJobFactory extends SpringBeanJobFactory {

    private AutowireCapableBeanFactory beanFactory;

    public void setApplicationContext(ApplicationContext context) {
        this.beanFactory = context.getAutowireCapableBeanFactory();
    }

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        Object job = super.createJobInstance(bundle);
        beanFactory.autowireBean(job);
        return job;
    }
}
