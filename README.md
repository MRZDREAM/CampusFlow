# CampusFlow

校园共享工作站的资源预约与候补服务。业务以固定时段和有限名额为基础，围绕预约、取消、候补晋升与名额一致性逐步实现。

## 已实现

- Java 21、Spring Boot 3.5.16、Spring MVC 应用基础。
- Maven Wrapper 固定构建工具版本，构建阶段检查 Java 与 Maven 版本。
- 健康检查、存活探针、就绪探针；管理端点仅开放健康信息。
- HTTP 404 / 405 使用 RFC 9457 Problem Details 响应。
- 不可变时段与容量对象，校验时间边界、区间重叠、预约与释放的容量约束。
- 领域边界测试和真实 HTTP 集成测试。
- GitHub Actions 在 Windows / Linux 上执行构建与测试。

当前领域对象尚未接入数据库或预约接口。持久化、认证、Redis、RabbitMQ 与候补流程属于后续实现范围；性能结果将在真实压测后记录。

## 构建与运行

需要完整的 JDK 21。`JAVA_HOME` 指向 JDK 根目录；首次构建需要联网下载 Maven 与项目依赖。

Windows（CMD 或 PowerShell）：

```text
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode --no-transfer-progress verify
java -jar target/campus-flow-0.1.0-SNAPSHOT.jar
```

Linux / macOS：

```sh
./mvnw --version
./mvnw --batch-mode --no-transfer-progress verify
java -jar target/campus-flow-0.1.0-SNAPSHOT.jar
```

应用默认监听 `8080`，可通过 `PORT` 环境变量修改。健康检查：

```sh
curl http://localhost:8080/actuator/health
```

预期返回 HTTP 200，JSON 中的 `status` 为 `UP`。`/actuator/health/liveness` 和 `/actuator/health/readiness` 分别用于存活、就绪检查；根健康端点还会列出探针分组名称。当前探针只反映应用状态；接入外部依赖后需重新定义就绪条件。

## 项目结构

```text
src/main/java/io/github/mrzdream/campusflow/
├── CampusFlowApplication.java
└── resource/domain/
    ├── SlotWindow.java
    ├── SlotCapacity.java
    └── CapacityUnavailableException.java
```

业务代码按功能模块组织。领域对象负责局部业务规则；持久化事务、数据库约束及并发控制由后续应用服务与存储层共同实现。

## 设计与演进

业务规则、模型关系及并发边界见 [领域设计](docs/domain-model.md)。后续依次实现数据库预约闭环、并发控制、认证、Redis / Lua、异步预约、候补与故障恢复。
