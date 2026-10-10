# isolate-rpc：轻量级 RPC 与服务隔离

基于 [Snailclimb/guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework) 的 2025 固定快照，保留 Apache-2.0 许可证和上游 Git 历史。新增请求生命周期治理、服务级有界执行器和可复现评测。这是学习型框架，不按生产级 Dubbo 宣传。

## 分支与来源

- `main` / `baseline/2025`：原始 V0，固定 `e2f0dd1be07770da08f38d91a8649e4cdf562ac8`。
- `project/rpc-isolation`：当前开发分支，按功能拆分提交。
- `upstream`：Snailclimb/guide-rpc-framework；`origin`：Jojen6324/isolate-rpc。
- V1/V2 使用同一构建产物，分别加载 shared/isolated 配置，避免混入修复或 Channel 分发差异。

[基线实测](docs/BASELINE.md)、[设计与边界](docs/DESIGN.md)、[原始计划](docs/RESEARCH_PLAN.md)、[上游 README](docs/UPSTREAM_README.md)。2026 上游参考回补与自己新增的隔离、测试、评测在设计文档中区分；未整体 cherry-pick，也未复制 Dubbo benchmark 源码。

## 开发与测试

环境是 Linux x86_64、Temurin 8u462-b08、Maven 3.9.9、ZooKeeper 3.5.8。Python 3.12+ 用于校验、启动与汇总；图表额外需要 matplotlib。

```bash
./scripts/setup-dev.sh
./scripts/zk.sh start
./scripts/mvn.sh test
python3 scripts/test_summarize.py
python3 scripts/fault-report.py --repeat 3 --output results/faults/fault-results.json
```

工具存放 `/workspace/toolchains`，依赖缓存 `/workspace/.m2/repository`；可用 RPC_TOOLCHAINS、RPC_MAVEN_REPO 覆盖。脚本采用发布者校验和、TLS、系统信任库和环境网络代理，不禁用验证，不写凭据。默认 ZooKeeper 使用独立本地数据目录；测试会写 `/my-rpc`，不要将该配置连接到生产注册中心。常规测试同时执行 JUnit 4 与 JUnit 5；Socket 示例保持编译。

## ZooKeeper 示例

```bash
./scripts/mvn.sh install dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
./scripts/example.sh server
# 在另外两个终端分别执行；-ea 已启用，每个客户端包含 11 次调用
./scripts/example.sh client
```

服务端绑定机器局域网地址 9998，ZooKeeper 地址 127.0.0.1:2181；正常终止服务端会清理本次发布的地址节点。旧注册路径使用持久节点，kill -9 后自动摘除不在当前能力范围内。结束示例后停止自己启动的服务端，避免与评测端口冲突；本地 ZooKeeper 可用 `./scripts/zk.sh stop` 停止。云任务本身已隔离，使用当前检出，不需要创建 Git worktree。

## SHARED / ISOLATED

配置位于 `configs/shared.properties`、`configs/isolated.properties`：共享 16 线程/队列 200；隔离快慢各 8/100。两模式统一分发、有界 ArrayBlockingQueue 和 AbortPolicy，拒绝回复 requestId + 503，客户端得到 OVERLOADED 类型。接口/group/version 组成结构化 ServiceKey；静态单服务覆盖用 RpcExecutorConfig.withService。服务清单启动时固定。

异步入口是 `NettyRpcClient.sendRpcRequestAsync(request, timeoutMillis)`，返回 CompletableFuture；超时覆盖发现、连接和调用全过程。同步代理继续可用。客户端 close 清理本实例资源；取消只结束本地请求，不等于远程业务停止。没有自动重试、端到端 deadline 或服务端异步返回值支持。

## 评测

默认参数用于较长采样；先运行短冒烟。评测服务端和客户端分 JVM，稳态直接连接配置地址，不把 ZooKeeper 查找计入延迟。脚本每次启动都会做实际内容校验 RPC；每个 JMH 外层 fork 都重启服务端。

```bash
./scripts/benchmark.sh jmh --mode shared --threads 16 --payload 128 --metric throughput
./scripts/benchmark.sh jmh --mode isolated --threads 16 --payload 128 --metric sample
./scripts/benchmark.sh scenario --mode shared --scenario slow-overload
./scripts/benchmark.sh scenario --mode isolated --scenario slow-overload
# 快速冒烟：三次交替 A/B，加多连接、角色互换、健康场景及 JMH 矩阵
./scripts/validate-benchmarks.sh smoke
# 较长实验（默认每组预热 30s、健康 60s、过载 120s、恢复 60s）
./scripts/validate-benchmarks.sh full
python3 scripts/summarize.py results --output results/summary
```

主要参数：--connections、--max-inflight、--timeout、--payload、--fast-rate、--slow-rate、--healthy-slow-rate、--swap-roles、各阶段 --*-seconds；JMH 使用 --threads、--forks、--warmup-iterations、--iterations、--warmup-time、--measurement-time。--no-build 仅供已完成构建后复用相同产物。脚本拒绝覆盖已有输出目录；每次生成独立目录。

每次保留 manifest.json、JMH 原生 JSON 或逐请求 scenario.csv、服务端 metrics.csv、client-metrics.csv、日志、client-statistics.json。故障报告独立保存当前运行 JUnit XML、断言、状态及实际竞态分布。汇总区分成功、过载、超时、传输/业务失败、未发出；分位数由原始成功样本计算，不能平均每秒 P95。生成器无效的运行不纳入 A/B 结论。

结果与四条简历/面试说明将在 `docs/MEASUREMENTS.md` 和 `docs/RESUME_NOTES.md` 记录；短采样数字只作为此机器上的初步证据，不替代正式容量评估。
