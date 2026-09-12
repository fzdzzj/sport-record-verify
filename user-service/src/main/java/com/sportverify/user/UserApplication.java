package com.sportverify.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类。
 *
 * <p>账号注册/登录 + 双向好友（好友申请/同意/拒绝/列表 + Redisson 锁防并发互加）。</p>
 * <p>扫描根包 {@code com.sportverify} 以加载 common 模块的全局异常处理器；
 * {@code @MapperScan} 扫描 user 域持久层（FriendRequestMapper/FriendRelationMapper 等）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
@MapperScan("com.sportverify.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
