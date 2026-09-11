package com.sportverify.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类。
 *
 * <p>账号注册/登录 + 双向好友（骨架阶段仅健康端点与中间件接入）。</p>
 * <p>扫描根包 {@code com.sportverify} 以加载 common 模块的全局异常处理器。</p>
 */
@SpringBootApplication(scanBasePackages = "com.sportverify")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
