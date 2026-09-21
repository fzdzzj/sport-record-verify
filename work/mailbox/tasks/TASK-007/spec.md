# TASK-007 RecordService 每日数据汇总定时任务

## 目标
为 RecordService 增加每日数据汇总定时任务，使用 XXL-JOB 框架实现。

## 范围外
- 不改动其他服务
- 不修改业务逻辑，仅增加定时任务调度

## 先读文件
- record-service/src/main/java/com/sportverify/record/service/RecordService.java
- record-service/pom.xml
- user-service/src/main/resources/application.yml

## 只改文件
- record-service/src/main/java/com/sportverify/record/job/DailyDataSummaryJobHandler.java（新建）
- record-service/pom.xml（添加 xxl-job-core 依赖）
- user-service/src/main/resources/application.yml（注释 XXL-JOB 配置部分）

## JobHandler 设计
```java
package com.sportverify.record.job;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每日数据汇总 JobHandler
 * XXL-JOB 定时任务处理器
 */
@Component
public class DailyDataSummaryJobHandler extends IJobHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyDataSummaryJobHandler.class);

    /**
     * 定时任务执行方法
     * @param params 任务参数
     * @return ReturnT 执行结果
     */
    @Override
    public ReturnT<String execute(String params) throws Exception {
        logger.info("DailyDataSummaryJobHandler start at: {}", System.currentTimeMillis());
        
        try {
            // TODO: 实现每日数据汇总逻辑
            // 1. 查询昨日所有记录
            // 2. 按用户/运动类型聚合统计
            // 3. 写入汇总表
            // 4. 返回执行结果
            
            logger.info("DailyDataSummaryJobHandler completed successfully");
            return ReturnT.SUCCESS;
        } catch (Exception e) {
            logger.error("DailyDataSummaryJobHandler failed: ", e);
            return new ReturnT<>(ReturnT.FAIL_CODE, "Task failed: " + e.getMessage());
        }
    }
}
```

## XXL-JOB 配置项
需要在 application.yml 中添加以下配置（当前需注释）：
```yaml
xxl:
  job:
    admin:
      addresses: http://xxl-job-admin:8080/xxl-job-admin
    executor:
      appname: record-service-executor
      registry:
        ip: ${spring.cloud.client.ip-address}
        port: ${server.port}
      logpath: /app/logs/xxl-job
      logretentiondays: 30
```

## 验收命令
```bash
cd record-service && mvn -B -ntp clean compile
mvn -B -ntp test-compile
```

## 完成定义
- JobHandler 类创建成功
- xxl-job-core 依赖添加到 pom.xml
- application.yml 中 XXL-JOB 配置已注释并说明
- 编译测试通过

## 不准猜测
- 如果 user-service/application.yml 无 xxl-job 配置 → 注释掉配置部分并说明"需先配置 XXL-JOB Admin 地址"
- 在 handoff.md 中明确写出配置缺失状态
