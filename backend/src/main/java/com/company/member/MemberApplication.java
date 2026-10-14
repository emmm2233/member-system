package com.company.member;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 会员管理系统启动类
 *
 * <p>技术栈：Spring Boot 3.2 + MyBatis-Plus + MySQL 8 + JWT
 * <p>核心设计：资产变动统一入口 AccountService；三层幂等；T+1 对账
 */
@SpringBootApplication
@MapperScan("com.company.member.modules.**.mapper")
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
public class MemberApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
