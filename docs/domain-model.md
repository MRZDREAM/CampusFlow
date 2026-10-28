# 资源预约领域设计

## 业务边界

首个场景是校园实验室的共享工作站。资源代表一组可预约的工作站，资源时段确定服务时间和名额，每笔预约消耗一个名额。

暂定规则：

1. 资源时段使用 UTC 时间点表示，展示层可以转换为用户时区。
2. 时间区间采用 `[startsAt, endsAt)`，起点包含、终点不包含。同一资源的相邻时段不重叠。
3. 时段容量必须大于零，占用名额始终在 `[0, total]` 内。
4. 一名用户在同一时段至多拥有一笔有效预约。
5. 已取消的预约保留记录；取消后可以重新预约。
6. 重复取消返回当前状态，不重复释放名额。
7. 已开始的时段不接受新预约；预约前仍需检查资源与时段是否开放。
8. 同一资源不允许创建重叠时段。查询后插入必须有数据库并发保护，不能只依赖 Java 中的重叠判断。

## 模型关系

| 模型 | 职责 | 关键字段（设计草稿） |
| --- | --- | --- |
| User | 业务身份 | id、username、passwordHash |
| Resource | 资源及开放状态 | id、name、description、status |
| ResourceSlot | 固定时段与容量 | id、resourceId、startsAt、endsAt、capacity、reserved、status、version |
| ReservationOrder | 预约生命周期 | id、userId、slotId、status、createdAt、cancelledAt |
| WaitlistEntry | 候补顺序与确认期限 | id、userId、slotId、status、createdAt、offeredAt、offerExpiresAt |

User 与 ResourceSlot 通过 ReservationOrder 关联。Resource 与 ResourceSlot 是一对多。候补记录在候补阶段加入。

首个同步版本的预约状态为 `CONFIRMED` / `CANCELLED`。异步请求的处理状态、预约业务状态和候补状态分别管理。

## 当前实现

`SlotWindow` 校验起止时间，并提供包含判断与对称的区间重叠判断。

`SlotCapacity` 是不可变值对象，预约返回占用加一的新值，取消返回占用减一的新值。满额时抛出 `CapacityUnavailableException`，无占用时释放视为非法状态。所有返回值仍需满足容量约束。

这两个对象尚未持久化。不可变对象可以避免共享对象被意外修改，但不会自动解决数据库并发问题：两个请求仍可能读取相同容量并各自计算新的占用值。

## 数据库版本的验收要求

- 使用 MySQL、版本化迁移与事务实现预约、取消闭环。
- 容量更新与订单写入必须在同一事务中完成。
- 通过版本条件更新及受影响行数判断并发冲突；重试必须开启新的事务，并设置次数上限。
- 数据库约束阻止重复有效预约，并允许取消后重约；不能仅使用永久的 `(user_id, slot_id)` 唯一约束。
- 取消通过状态条件更新确保仅发生一次有效转换；名额释放与状态转换处于同一事务。
- 重叠时段创建须有一致的锁定规则；对同一资源的检查与插入进行串行保护。
- 并发实验核对最终订单数、容量与重复订单，不能仅统计 HTTP 成功数。

已通过 Flyway 建立 resources 和 resource_slots，并实现非空名称、开放状态、时间范围、名额范围、非负版本号及资源外键约束。状态列使用区分大小写的排序规则。资源时段使用 (resource_id, starts_at) 联合索引支持资源内的时段查询。

starts_at / ends_at 使用 DATETIME(6)，保存 UTC 时间；该类型本身不携带时区，数据库会话固定为 UTC，后续数据访问层也必须遵循同一约定。version 列目前只保存初始版本号；尚无带版本条件的更新逻辑，不能据此宣称已经实现乐观锁。区间重叠、重复预约和取消幂等仍需后续业务与数据库并发控制。

真实 MySQL 集成测试验证迁移与上述字段、关联约束。预约事务和并发测试尚未实现。

资源管理已通过 JPA 映射 resources 的 id、name、description、status。创建时规范化名称，缺失描述保存为空字符串，状态初始化为 OPEN；客户端不能通过创建请求指定编号或状态。资源名称不唯一。同名设备通过 id 区分。

资源接口提供创建、按编号读取和按编号升序分页读取。页号为 0～10000，每页 1～100 条，默认 20。读取关闭状态资源也会返回其资料，当前没有状态修改接口。列表采用普通偏移分页，多次查询在并发写入时不保证同一快照。

资源的创建和更新时间由数据库维护，当前 JPA 实体暂不映射这些列。状态枚举明确映射为 VARCHAR，与现有迁移保持一致。Hibernate 仅 validate，迁移仍由 Flyway 执行。

## 接口草稿

| 方法与路径 | 行为 |
| --- | --- |
| POST /api/v1/resources | 已实现：创建资源，返回 201 和详情 Location |
| GET /api/v1/resources | 已实现：分页资源列表 |
| GET /api/v1/resources/{id} | 已实现：查询详情；不存在返回 404 |
| POST /api/v1/resources/{resourceId}/slots | 创建资源时段 |
| GET /api/v1/resources/{resourceId}/slots | 查询资源时段与可用名额 |
| POST /api/v1/reservations | 创建预约 |
| GET /api/v1/reservations/{reservationId} | 查询预约状态 |
| POST /api/v1/reservations/{reservationId}/cancellation | 取消预约 |

资源创建和查询已实现，其余业务接口仍是草稿。认证与操作授权尚未接入；认证接入后，业务用户身份从认证上下文取得，接口不能信任客户端传入的任意用户身份。
