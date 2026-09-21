# TASK-023 部署运维

## 目标
Docker/K8s 编排、健康检查、日志收集。对用户敏感数据增加脱敏处理。

## 只改文件
- leaderboard-service/Dockerfile（新建）
- leaderboard-service/k8s/deployment.yaml（新建）
- leaderboard-service/pom.xml（添加 spring-boot-starter-actuator）
- common/src/main/java/com/sportverify/common/util/SensitiveUtils.java（新建，需新建脱敏工具类）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/entity/*.java（@Masked 注解标记敏感字段）

## 脱敏注解设计
### @Masked 注解
位置：`common/src/main/java/com/sportverify/common/annotation/Masked.java`

```java
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Masked {
    /** 脱敏类型 */
    MaskType type() default MaskType.DEFAULT;
    
    /** 自定义掩码字符，默认 * */
    String maskChar() default "*";
}

enum MaskType {
    DEFAULT,        // 默认脱敏（手机号/身份证等根据规则）
    PHONE,          // 手机号脱敏：138****1234
    ID_CARD,        // 身份证号脱敏：110***********1234
    NAME,           // 姓名脱敏：张*三
    EMAIL,          // 邮箱脱敏：zhang***@example.com
    NONE            // 不脱敏（用于测试）
}
```

### SensitiveUtils 工具类
位置：`common/src/main/java/com/sportverify/common/util/SensitiveUtils.java`

```java
public class SensitiveUtils {
    /**
     * 对对象进行脱敏处理（反射遍历带@Masked 注解的字段）
     */
    public static Object mask(Object obj);
    
    /**
     * 单个值脱敏
     */
    public static String maskValue(String value, MaskType type);
}
```

### 实体类使用示例
```java
@Data
public class UserDTO {
    private Long id;
    
    @Masked(type = MaskType.PHONE)
    private String phone;
    
    @Masked(type = MaskType.NAME)
    private String name;
}
```

**注意**：由于 common 模块无现成工具类，上述代码需新建。若无法实现完整功能，注释掉"需要修改"部分并标注"需新建脱敏工具类"。

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp package
docker build -t leaderboard-service:latest .
kubectl apply -f k8s/deployment.yaml
```

## 完成定义
- Docker 镜像构建成功
- K8s 部署正常
- 健康检查通过
- 敏感字段脱敏处理生效
