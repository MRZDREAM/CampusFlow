# 三种预约方式的同口径测试

需要 PowerShell 7、已启动的完整模式应用和管理员凭据。在项目根目录，分别执行：

```powershell
pwsh -NoProfile -File scripts/benchmark.ps1 -Mode DB -Users 100 -Capacity 10 -Concurrency 32
pwsh -NoProfile -File scripts/benchmark.ps1 -Mode REDIS -Users 100 -Capacity 10 -Concurrency 32
pwsh -NoProfile -File scripts/benchmark.ps1 -Mode MQ -Users 100 -Capacity 10 -Concurrency 32
```

每次用真实注册与登录生成独立用户、资源和时段；准备过程不计入请求时间。每个用户发一次请求，最多有指定数量同时执行。MQ 模式轮询到终态，因此分别记录提交和最终结果延迟。拒绝是竞争有限名额时的正常业务结果；超时和非预期响应另计。最后校验确认数等于数据库占用，且不大于容量。

脚本输出请求数量、确认、拒绝、错误、数据库占用、P95 和每秒完成的尝试数。P95 包含成功与业务拒绝；拒绝快可能降低整体延迟。三条路径在同一应用版本上对比，结果取决于机器、负载、队列与数据库，不能直接推广到生产。运行不同模式时保持机器、容量、并发和用户数相同。可传 -Users 500 或 -Users 1000 追加样本；-Concurrency 是同时执行上限，不能把用户数写成同时在线数。

重复运行保留独立命名的演示数据。建议专用演示库，结束后按需重置该库；不要在业务库跑压测。CI 的输出可作为一次可追溯验证，不能当作生产性能承诺。
