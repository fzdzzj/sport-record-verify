# TASK-012 Gateway 安全增强

## 目标
为 GatewayService 增加 CSRF 防护和密码加密功能，提升网关层安全性。

## 只改文件
- gateway-service/pom.xml（添加 spring-security 依赖）**需先添加依赖**
- gateway-service/src/main/java/com/sportverify/gateway/config/SecurityConfig.java（新建）
- gateway-service/src/main/resources/application.yml（追加安全配置）

## SecurityFilterChain 配置
### 策略选择
- **CSRF 防护**: 针对 API 接口禁用 CSRF（Gateway 无会话，采用 token-based 认证）
- **密码加密**: 使用 BCryptPasswordEncoder 对管理后台密码加密存储
- **路径规则**: 
  - `/admin/**` 路径启用基础认证 + BCrypt 密码校验
  - `/actuator/**` 仅允许内网访问
  - 其他路径维持原有 JWT 鉴权逻辑

### 代码实现
```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable()) // Gateway 无会话，禁用 CSRF
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/admin/**").authenticated()
                .pathMatchers("/actuator/**").permitAll()
                .anyExchange().access(new IpAuthorizationMatcher())
            )
            .httpBasic(basic -> basic);
        
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 密码加密示例
```java
// 注册管理员时加密密码
String encodedPassword = passwordEncoder.encode(rawPassword);
// 验证密码
boolean matches = passwordEncoder.matches(rawPassword, encodedPassword);
```

### application.yml 配置
```yaml
spring:
  security:
    user:
      name: admin
      password: "{bcrypt}$2a$10$..." # BCrypt 加密后的密码
```

## 验收命令
```bash
mvn -B -ntp test -Dtest=SecurityConfigTest
```

## 完成定义
- CSRF 已正确禁用（Gateway 场景）
- BCrypt 密码加密/解密正常
- 路径权限控制生效

## 说明
**需先添加依赖**: gateway-service/pom.xml 当前无 spring-security 依赖，注释掉"需要修改"部分并说明"需先添加依赖"。
后续需在 pom.xml 中添加 `spring-boot-starter-security` 依赖后方可完整实现。
