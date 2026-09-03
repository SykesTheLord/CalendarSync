package com.sykessec.calendarsync.config;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz instantiates Job classes itself (they need a no-arg constructor),
 * which bypasses normal Spring dependency injection - a plain JobFactory
 * @Bean is NOT picked up automatically by Boot's QuartzAutoConfiguration
 * (confirmed by boot log still showing the framework's own default
 * SpringBeanJobFactory), so this uses the documented
 * SchedulerFactoryBeanCustomizer hook instead, which IS guaranteed to run.
 * The result: ConnectionSyncJob's @Autowired fields get populated right
 * after Quartz creates each job instance, same as any other Spring bean.
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer autowiringJobFactoryCustomizer(ApplicationContext applicationContext) {
        return schedulerFactoryBean -> {
            AutowiringSpringBeanJobFactory factory = new AutowiringSpringBeanJobFactory(
                    applicationContext.getAutowireCapableBeanFactory());
            schedulerFactoryBean.setJobFactory(factory);
        };
    }

    private static class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory {

        private final AutowireCapableBeanFactory beanFactory;

        AutowiringSpringBeanJobFactory(AutowireCapableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
            Object job = super.createJobInstance(bundle);
            beanFactory.autowireBean(job);
            return job;
        }
    }
}
