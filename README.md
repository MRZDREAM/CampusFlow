# CampusFlow

校园共享工作站的资源预约与候补服务。业务以固定时段和有限名额为基础，围绕预约、取消、候补晋升与名额一致性逐步实现。

## 已实现

- Java 21、Spring Boot 3.5.16、Spring MVC 应用基础。
- Maven Wrapper 固定构建工具版本，构建阶段检查 Java 与 Maven 版本。
- 健康检查、存活探针、就绪探针；管理端点仅开放健康信息。
- HTTP 404 / 405 使用 RFC 9457 Problem Details 响应。
- 不可变时段与容量对象，校验时间边界、区间重叠、预约与释放的容量约束。
- 领域边界测试和真实 HTTP 集成测试。
- MySQL 8.4 数据源与连接池，UTC 会话时间和外置本地凭据。
- Flyway 11.20.3 版本化建表、校验已应用脚本；资源与时段的数据库约束、外键和查询索引。
- 真实 MySQL 集成测试：迁移重入、校验和变更、字段约束、关联完整性与就绪探针。
- 资源创建、详情与分页列表接口；请求校验、名称规范化和明确的 400 / 404 错误响应。
- JPA 映射校验、服务层事务和独立响应 DTO；表结构仍由 Flyway 管理。
- GitHub Actions 在 Windows / Linux 上执行构建与测试。

资源管理接口已接入数据库。时段管理接口、预约事务和并发控制尚未实现。当前接口用于开发验证，身份认证与操作授权尚未接入。Redis、RabbitMQ 与候补流程属于后续实现范围；性能结果将在真实压测后记录。

## 构建与运行

需要完整的 JDK 21。`JAVA_HOME` 指向 JDK 根目录；首次构建需要联网下载 Maven 与项目依赖。运行应用需要 MySQL 8.4。

将 `.env.example` 复制为 `.env`，替换两个示例密码，分别用于应用用户和数据库管理员。`.env` 使用 properties 兼容格式，值不额外包引号；文件不会纳入 Git。应用从当前工作目录读取该文件，系统环境变量可以覆盖其中的配置。

如果已安装 Docker Compose，可在项目根目录启动固定版本的本地数据库：

```text
docker compose up -d --wait mysql
```

默认端口为 `127.0.0.1:13306`，数据库为 `campus_flow`，应用账号为 `campus_flow`。初始化脚本还创建独立的 `campus_flow_it` 测试库，并赋予该账号测试库权限。已有数据卷不会重复执行容器初始化脚本。

也可以使用自行安装的 MySQL。需事先创建上述两个数据库和应用账号，并将连接信息写入 `.env` 或对应环境变量。应用不使用 root 账号。容器停止命令为 `docker compose stop mysql`，停止不会清空数据卷。

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

预期返回 HTTP 200，JSON 中的 `status` 为 `UP`。`/actuator/health/liveness` 检查应用存活状态；`/actuator/health/readiness` 同时检查应用就绪状态和数据库连接。数据库中断后，就绪检查应返回 HTTP 503，存活检查不因数据库中断而失败。健康检查不公开组件详情。

## 数据库迁移与验证

应用启动时，Flyway 按顺序执行 `src/main/resources/db/migration` 中尚未应用的 SQL，并在 `flyway_schema_history` 保存版本与校验和。V1 建立两张业务表；V2 将状态列改为区分大小写，使 `open` 无法绕过只允许 `OPEN` / `CLOSED` 的约束。

已执行的迁移文件保持不变，结构变更使用后续版本。MySQL DDL 不能统一依靠事务回滚，迁移失败时需核查实际结构后修复。Flyway clean 已禁用，已有非空数据库也不会被自动 baseline。

普通 `verify` 执行领域测试与不依赖数据库的 HTTP 合约测试。准备好独立测试库后，执行完整 MySQL 验证：

```text
.\mvnw.cmd --batch-mode --no-transfer-progress -Pmysql-it verify
```

Linux / macOS 使用 `./mvnw`。`mysql-it` 是 Maven 构建配置；它启用 Failsafe 执行 `DatabaseMigrationIT` 和 `ResourceApiIT`，不是 Spring profile。集成测试固定使用 `campus_flow_it`。数据库约束测试的数据通过事务回滚；真实 HTTP 测试在请求提交后，仅清理各自生成的随机名称记录。测试不调用 Flyway clean。

CI 除 Windows / Linux 的普通构建外，还在独立 MySQL 服务中执行同一套数据库测试。工作流中的密码仅用于每次运行时创建并销毁的 CI 数据库。

## 资源接口

| 方法与路径 | 行为 | 成功响应 |
| --- | --- | --- |
| POST /api/v1/resources | 创建资源 | 201，Location 指向详情地址 |
| GET /api/v1/resources/{id} | 按正整数编号查询 | 200；不存在时为 404 |
| GET /api/v1/resources?page=0&size=20 | 按编号升序分页查询 | 200，含 content 和分页统计 |

创建请求只接收 name 和 description。名称去掉首尾空白后必须有内容，长度上限为 120；描述可省略，上限为 1000。长度按 Java 字符串的 UTF-16 单元计。编号由数据库分配，初始状态由服务端设为 OPEN。请求中的额外字段当前会被忽略，不能用于指定编号或状态。

```json
{"name":"GPU 工作站","description":"共享计算设备"}
```

响应字段为 id、name、description、status。未提供描述时返回空字符串。名称不要求唯一，同名资源可以代表不同设备。

分页从 0 开始，page 范围为 0～10000，size 范围为 1～100，默认分别为 0 和 20。超出已有数据的页返回空 content。采用独立分页响应，避免把框架内部对象作为公开 JSON 合约；并发插入期间不保证多次分页请求共享同一快照。

非法字段返回 400 / VALIDATION_FAILED，并列出 errors；错误 JSON 或参数类型返回 400 / INVALID_REQUEST；资源不存在返回 404 / RESOURCE_NOT_FOUND。错误响应使用 application/problem+json。

Hibernate 使用 ddl-auto: validate，只校验已映射的列，不修改结构。资源创建和更新时间仍由数据库默认值维护，本批暂不映射和返回这些列。open-in-view 关闭，响应 DTO 在服务事务内构建。

## 项目结构

```text
src/main/java/io/github/mrzdream/campusflow/
├── CampusFlowApplication.java
├── resource/
│   ├── api/                 # HTTP 路由
│   ├── application/         # 服务与事务
│   ├── domain/              # 资源实体、状态和时段值对象
│   ├── dto/                 # 请求与响应数据
│   └── persistence/         # JPA Repository
└── web/                     # HTTP 异常响应
```

业务代码按功能模块组织。领域对象负责局部业务规则；资源服务组织创建和查询事务。预约涉及的多表一致性与并发控制将在相应模块实现。

## 设计与演进

业务规则、模型关系及并发边界见 [领域设计](docs/domain-model.md)。后续依次实现数据库预约闭环、并发控制、认证、Redis / Lua、异步预约、候补与故障恢复。
