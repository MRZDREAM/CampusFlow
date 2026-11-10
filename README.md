# CampusFlow

校园共享工作站预约与候补服务。管理员维护资源和时段；用户查看可用名额、预约、取消，满额时加入候补并在期限内确认。

- **MySQL**：版本化建表，保存账号、时段、订单、候补和异步请求；名额变更与业务记录在同一事务内提交。
- **Redis**：时段查询短期缓存；Lua 原子检查重复在途请求和临时入口额度。最终预约结果由 MySQL 决定。
- **RabbitMQ**：持久化异步请求、发布确认、手动消费确认、延迟重试和死信。提交返回请求编号，可查询最终状态。
- **身份与观察**：密码使用 BCrypt 哈希，JWT 区分管理员与普通用户；Swagger UI、健康探针和管理员 Prometheus 指标。

业务和数据规则见 [领域设计](docs/domain-model.md)，故障行为见 [故障验证](docs/failure-cases.md)。

## 完整模式运行

准备 JDK 21、Docker Compose 和 PowerShell。在项目根目录执行：

```powershell
.\scripts\init-local.ps1
docker compose up -d --wait mysql redis rabbitmq
.\mvnw.cmd --batch-mode --no-transfer-progress verify
$env:SPRING_PROFILES_ACTIVE = 'full'
java -jar target/campus-flow-0.1.0-SNAPSHOT.jar
```

初始化脚本生成仅本机使用的 .env 凭据，保留已有的非空配置。应用首次启动时按配置创建管理员；改启动密码不会覆盖已经存在的管理员密码。若原生 MySQL 已在 13306 运行，只启动容器里的 Redis 和 RabbitMQ，并继续连接原生 MySQL。Linux / macOS 可用 `./mvnw` 构建、用 `SPRING_PROFILES_ACTIVE=full java -jar ...` 启动。

默认地址：

| 功能 | 地址 |
| --- | --- |
| API 与 Swagger UI | http://localhost:8080/swagger-ui/index.html |
| 就绪检查 | http://localhost:8080/actuator/health/readiness |
| Redis | 127.0.0.1:16379 |
| RabbitMQ AMQP / 管理页 | 127.0.0.1:5672 / http://127.0.0.1:15672 |
| MySQL | 127.0.0.1:13306 |

RabbitMQ 管理页用 .env 中的 RABBIT_USERNAME（缺省 campus_flow）和 RABBIT_PASSWORD。容器端口只绑定到本机。可用 `docker compose stop mysql redis rabbitmq` 停止容器并保留数据。

本机只验证数据库功能时，可不启用 full。Redis 模式和异步路由需要 full。未提供 CAMPUS_JWT_KEY 时，服务使用进程内随机签名密钥，重启后旧令牌失效；多实例应配置同一把私密密钥。生产部署还需按实际环境设置 TLS、凭据管理及备份。

## API 主线

| 方法与地址（前缀 /api/v1） | 行为 |
| --- | --- |
| POST /auth/register、/auth/login | 注册普通账号、登录取得 Bearer JWT |
| POST /resources、PATCH /resources/{id}/status | 管理员创建、开放或关闭资源 |
| GET /resources、/resources/{id} | 查询资源分页与详情 |
| POST /resources/{id}/slots | 管理员创建 UTC 时段；相邻允许，重叠拒绝 |
| GET /resources/{id}/slots、/slots/{slotId}（资源路径下） | 查询最多 100 个未来时段或指定时段 |
| PATCH /resources/{id}/slots/{slotId}/status | 管理员调整时段开放状态 |
| POST /reservations?mode=DB 或 REDIS | 同步预约指定时段；请求体含 slotId |
| GET /reservations、/reservations/{id} | 查询自己的预约，可传 beforeId 获取更早记录 |
| POST /reservations/{id}/cancellation | 取消自己的预约，重放只释放一次 |
| POST /waitlist、GET /waitlist、GET /waitlist/{id} | 加入和查询自己的候补；加入请求体含 slotId |
| POST /waitlist/{id}/confirmation 或 /cancellation | 确认限时名额，或退出候补 |
| POST /reservation-requests | 异步预约，带 UUID 类型 Idempotency-Key，返回 202 和 Location |
| GET /reservation-requests/{id} | 查询自己的异步请求是否 PENDING / SUCCEEDED / REJECTED / FAILED |

查询他人的订单或请求返回 404。资源和时段公开可读，管理操作要求管理员角色。202 表示请求已持久化，收到 SUCCEEDED 才表示预约成功。Swagger 的 Authorize 输入登录响应中的 accessToken。

## 演示、测试和压测

应用运行后，在另一个 PowerShell 窗口执行：

```powershell
.\scripts\demo.ps1
```

脚本通过真实接口生成三位模拟用户及独立时段，演示候补递补、重复取消、Redis 模式和异步预约。每次运行创建一组新的演示记录。仅 MySQL 模式使用 `-DatabaseOnly`。

- `./mvnw verify`：领域与 HTTP 测试。
- `./mvnw -Pmysql-it verify`：追加真实 MySQL 验证；Windows 使用 `.\mvnw.cmd`。测试数据库固定为 campus_flow_it。
- 同时设置 CAMPUS_FULL_IT=true 时，集成测试还使用 Redis 的 15 号数据库和 RabbitMQ 的 campus-flow-it 虚拟主机。该虚拟主机和权限需提前创建；CI 提供独立的服务实例。勿用业务虚拟主机代替。
- [压测方法](docs/benchmark.md) 包括三种预约方式、数据生成、统计口径和边界。健康及基础 HTTP 指标外，Prometheus 可观察缓存、Lua、发布重试、确认和死信等操作。

## 工程边界

Flyway V1–V4 逐次迁移，Hibernate 只校验映射。Redis 和消息队列支持独立故障与恢复，无法提供跨服务的单次事务；对账依据仍是 MySQL 中的订单、候补占用与请求终态。详情见 [领域设计](docs/domain-model.md)。
