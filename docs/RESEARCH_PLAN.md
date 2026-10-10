# 轻量级 RPC 框架：基座调研与完整改造计划

调研日期：2026-10-10。目标：补充 Java 后端简历，替换独立 Agent 项目；保留成熟的学习型 RPC 底座，完成一至两项可解释、可测试的改进。

## 1. 结论与版本选择

选择 [Snailclimb/guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework)。

- 开发基座：`e2f0dd1be07770da08f38d91a8649e4cdf562ac8`，Git 作者时间 2025-09-30 17:52:57 +08:00。
- 参考版本：`6e6f3132b3a34843b1e7a1baeec2be12c5dfa7cc`，2026-08-08，核心实现及健壮性改进。
- 参考版本：`704a4a82240d02fdcf89192c6d185cd07594b9f3`，2026-08-08，异步调用与错误处理改进。
- 外部设计参考：[Dubbo 服务级线程池隔离](https://dubbo.apache.org/en/docs3-v2/java-sdk/advanced-features-and-usage/service/isolation-executor/)。
- 项目主线：2025 版本 → 回补请求生命周期可靠性 → 新增服务级有界执行器 → 公平 A/B 压测。
- 建议项目名称：轻量级 RPC 框架与服务隔离优化。

这是 2025 年的代码快照，并非整个框架在 2025 年才出现。源代码中不少类源于 2020 年。只要版本固定、改进真实，不需要追求“今年发明”的标签。

为什么选它：生产 Java 源码规模可控，具备通信、代理、注册发现、序列化和扩展机制；有完整 Git 历史，可直接看今年如何修复去年版本的问题。它是学习型框架，不能按生产级 Dubbo 宣传。

## 2. 候选比较与调查深度

| 候选 | 已调查内容 | 本次定位 |
| --- | --- | --- |
| guide-rpc-framework 2025 快照 | 完整 Git 历史、POM、核心客户端/服务端/协议/注册代码、2026 差异 | 首选基座 |
| guide-rpc-framework 当前版本 | 核心代码与构建要求，84 个生产 Java 文件 | 参考实现；改造空间较少且构建要求较新 |
| Apache Dubbo | 官方仓库、线程模型与服务隔离文档 | 设计参考；本次不直接 fork 整套框架 |
| SOFABolt | 官方 README，通信模型、超时和连接管理能力 | 可参考通信治理；不是本次首选的完整注册发现/代理底座 |

guide-rpc-framework 2025 快照：76 个 src/main 下的 Java 文件，约 3,843 行；11 个 src/test/java 下的 Java 文件。这是文件数和物理行数，不是测试用例数量。
当前参考提交：84 个生产 Java 文件，约 5,403 行，32 个测试 Java 文件。

SOFABolt 自身是通信框架，已有连接管理、sync/future/callback、超时和心跳等能力。若直接选择它，还需构建上层服务发现与透明调用展示，范围更难控制。
Apache Dubbo 对本次“范围小、改动可解释”的个人项目而言，理解和复现实验的成本更高。这是选型判断，不是质量排名。

## 3. 源码检查发现：必须据此调整原计划

下列路径相对于 guide-rpc-framework 仓库；结论来自固定提交源码检查。运行复现尚待后续完成。

### 3.1 已有业务执行器，不能把 I/O 分离当作新功能

2025 的 `NettyRpcServer.java` 创建 `DefaultEventExecutorGroup`，线程数为 `RuntimeUtil.cpus() * 2`。
随后通过 `pipeline.addLast(serviceHandlerGroup, nettyRpcServerHandler)` 添加业务 Handler。

所以原版本已经把 Handler 从 Netty I/O 执行线程切换至业务执行器。
真实缺口是：执行器没有按服务划分资源；Handler 调用业务方法时会阻塞相应执行器；未设置项目级有界队列与可辨识的过载响应。

此外，默认执行器分配存在按 Channel 串行处理的影响。不能把消除同一 Channel 队头阻塞与服务隔离的效果混在一个指标里。

### 3.2 多连接 Handler 复用问题

2025 的 `NettyRpcServer.java` 在 ChannelInitializer 外创建一个 `NettyRpcServerHandler`，再把该实例加入多个 Channel。
但旧版 Handler 类没有 `@ChannelHandler.Sharable` 标记。这构成可复用性问题，需通过两个 Channel/两个客户端测试复现。

2026 参考版本增加了 Sharable 标记。自己的基线可以采用每个 Channel 独立 Handler，或在确认无共享可变请求状态后允许共享；先写回归测试。
不要未经线程安全检查直接给任意 Handler 加标记。

### 3.3 客户端无限等待与 pending 清理缺口

2025 的 `NettyRpcClient.sendRpcRequest()` 最终调用 `resultFuture.get()`，没有请求等待时限。
`doConnect()` 建连失败分支在监听器内抛异常，却没有异常完成用于等待连接的 Future。
写失败虽然异常完成调用 Future，但 pending Map 没有对应移除操作。
`UnprocessedRequests` 只有 put/complete；未知响应直接抛异常，没有 remove、连接失败清理和统一完成路径。

这些源码路径意味着断连、发送失败和不响应场景存在无限等待或 pending 残留风险。需要故障测试验证，不能只看正常调用成功。

2026 代码提供了请求超时、完成后的清理、迟到响应处理、连接关闭通知等参考实现。
适合按功能回补，不建议整体 cherry-pick：今年的提交还夹杂了大规模依赖升级和其他改动。

### 3.4 业务异常和 null 返回

旧服务端对业务异常没有稳定的逐请求失败响应；`result.toString()` 也会使正常 null 结果进入异常路径。
先补充请求级异常响应和 null 返回测试。普通业务异常不应破坏同一连接上其他正常请求。

### 3.5 注册发现的边界

旧版 `ZkServiceRegistryImpl` 实际调用的是 `createPersistentNode`；注册实现中存在临时节点辅助方法，不代表当前发布路径已经使用它。
因此先验证正常启动、注册和正常退出，不要直接宣称宕机后会自动清除服务节点。
进程 kill 后的自动摘除不纳入本轮主亮点；以后若实施临时节点和会话恢复，再单独验证。

## 4. 环境选择与本次验证边界

2025 POM：
- Java source/target：1.8。
- Netty：4.1.42.Final。
- Spring：5.2.7.RELEASE。
- Lombok：1.18.8。
- Kryo：4.0.2。
- Curator：4.2.0。

2026 POM：Java 25、Maven 3.9+、Netty 4.2 系列及 Spring 7，迁移范围明显更大。

建议第一轮使用 JDK 8 + Maven 3.9.x 构建旧基座，先稳定实验。若个人开发环境只能用 JDK 17，先做独立的构建兼容提交，例如更新 Lombok，并确认依赖运行兼容；不要一次性升级全部依赖。
升级后的平台必须在所有 A/B 方案中一致。

本次已经下载原仓库与完整历史，创建了固定 2025 提交的独立 worktree，安装了临时 JDK 8 与 Maven 3.9.9，并尝试原版本 `mvn -DskipTests package`。
构建在 Maven 插件下载阶段失败：首次为 Maven Central DNS 错误，按环境代理配置重试后连接被拒绝。失败尚未进入源码编译阶段。

因此：
- 已完成：源码调查、版本与缺口确认、构建尝试。
- 未完成：源码编译通过、全部测试通过、ZooKeeper 端到端调用、故障复现和压测。
- 当前基座是“源码层面适合改造”，不是“已验证一键可运行”。阶段 0 必须通过构建和双客户端调用后再推进。

## 5. 实施范围与代码改动位置

### 工作 A：请求生命周期治理（参考 2026 上游）

目标：每个请求在成功、业务失败、超时、取消、发送失败和连接关闭后都能终结；终结后释放 pending 条目及定时任务。

| 文件/模块 | 改动 |
| --- | --- |
| client/NettyRpcClient.java | 增加异步发送入口；建连失败异常完成；请求超时；写失败处理；统一收尾 |
| client/UnprocessedRequests.java | 引入 PendingRequest/RequestContext，记录 Future、Channel 与定时任务；按请求/连接清理；暴露只读 pending 数 |
| client/NettyRpcClientHandler.java | 响应匹配；迟到响应安全忽略并计数；断连只失败该连接的请求 |
| client/ChannelProvider.java | 移除失效 Channel，确保以后可重新连接 |
| proxy/RpcClientProxy.java | 保留同步调用体验；把异常转换为可识别的超时/过载/传输/业务异常 |
| RpcResponseCodeEnum/RpcResponse | 补充稳定错误码与带 requestId 的失败响应 |
| server/NettyRpcServerHandler.java | null 结果正常返回；业务失败按请求响应 |

约束：
1. 注册 pending 与超时/连接关闭之间必须考虑竞态；请求在注册前已终结时不得重新留下条目。
2. Future 终结可以有多个竞争触发者，但完成/清理结果只能生效一次。
3. 正常完成、超时和取消均取消关联计时任务，并移除关闭监听器等附属资源。
4. 断开 A 连接时不能清理 B 连接的请求；客户端整体 close 才清理全部。
5. 客户端取消不等于远程业务停止。本轮只保证本地等待与资源结束，不宣传服务端取消或端到端 deadline。
6. 请求超时从调用入口开始计时；发送前检查请求是否已终结。已发送的业务可能仍继续执行，不自动重试有副作用的请求。
7. 同步代理继续可用；压测通过异步发送入口驱动，不用大量阻塞客户端线程制造瓶颈。
8. 主要改造 Netty 路径。Socket 示例保持可编译，不将它纳入新增异步能力和性能宣传范围。

### 工作 B：服务级有界执行器（参考 Dubbo 的隔离思路）

新增：
- `ServiceKey`：接口、group、version 的结构化键，避免直接字符串拼接带来的歧义。仅用于执行器定位，先不改变 ZooKeeper 注册路径。
- `RpcExecutorConfig`：线程数、队列容量、共享/隔离模式与关闭等待时间。
- `ServiceExecutorManager`：启动时根据已发布服务建立执行器，统一获取、统计和关闭。
- `ExecutorMetrics`：活跃线程、队列大小、完成数与拒绝数。
- `FastService`、`SlowService`、`FaultService` 及独立压测入口。

修改：
- `RpcServiceConfig` 或独立配置表：服务与执行器参数关联。
- `NettyRpcServer`：初始化管理器；Handler 使用相同分发结构；关闭资源。
- `NettyRpcServerHandler`：解码后按 ServiceKey 分发到业务线程池；捕获拒绝并回复过载响应。
- `CustomShutdownHook`：先停止接收/提交新请求，再限时等待任务，最后结束资源；避免多处重复注册 Hook。

设计：
- 使用 `ThreadPoolExecutor` + 有界 `ArrayBlockingQueue` + `AbortPolicy`。
- SHARED 和 ISOLATED 模式通过同一个管理器、同一条分发代码切换。
- 请求入队失败，返回带原 requestId 的 RESOURCE_EXHAUSTED/OVERLOADED 响应；客户端立即得到类型化异常。
- Handler 负责轻量分发与心跳，不同步执行用户业务，也不在 I/O 线程执行阻塞服务发现。
- 每个服务只创建一个执行器，不在每次请求中创建线程池。
- 关闭已开始后拒绝新的业务提交；失败消息必须明确。
- 服务清单与配置在启动时固定；本轮不做动态注册/删除、热调参和虚拟线程。
- 第一轮服务返回普通同步结果。CompletionStage 服务端返回值支持可作后续项，不能靠排队容量限制异步业务的实际在途量。

## 6. 分支和实施顺序（含评测适配，预计 8～12 个有效工作日）

先在自己的 fork/独立开发仓库操作，保留 upstream 来源和许可证。

```bash
git clone https://github.com/Snailclimb/guide-rpc-framework.git
cd guide-rpc-framework
git switch -c project/rpc-isolation e2f0dd1be07770da08f38d91a8649e4cdf562ac8
```

| 阶段 | 时间估计 | 工作和验收 |
| --- | --- | --- |
| 0：环境与源码基线 | 0.5～1 天 | JDK 8 构建；启动 ZooKeeper；跑通单客户端；记录基座 SHA 与依赖 |
| 1：修复运行阻塞点 | 1 天 | 双连接 Handler、null/业务异常、requestId 失败响应；双客户端回归通过 |
| 2：请求生命周期 | 2 天 | 超时、发送失败、断连、迟到响应、客户端 close；pending 回归到基线 |
| 3：服务执行器 | 2 天 | SHARED/ISOLATED、服务配置、有界队列、明确过载响应、关闭 |
| 4：评测适配与场景验证 | 2～4 天 | JMH、混合负载、故障断言与重复 A/B，详见第 12 节 |
| 5：文档和简历 | 0.5～1 天 | 架构说明、原始结果、复现实验、四条简历与面试笔记 |

建议提交拆分：
1. build: 固定环境与启动说明。
2. fix: Handler 生命周期和逐请求错误响应。
3. feat: 请求超时及 pending 生命周期。
4. feat: 服务级有界执行器及过载错误。
5. test: 故障、隔离和关闭验证。
6. bench/docs: 负载发生器、对比数据与说明。

不要整体 cherry-pick 今年两个大型提交；按问题读取参考实现，写出可单独审核的小提交。

## 7. 必须有的正确性测试

| 场景 | 断言 |
| --- | --- |
| 两个客户端同时连接 | 两个 Channel 均可完成 RPC，避免共享 Handler 问题 |
| null 返回/业务抛异常 | 请求得到对应结果或业务失败；下一次正常调用仍成功 |
| 服务不响应 | 调用在设定超时和明确容差内异常完成；pending 恢复 |
| 建连失败 | 不会无限等待连接 Future |
| 写失败 | 调用失败并移除 pending |
| 关闭一个连接 | 只失败该连接在途请求；其他连接继续调用 |
| 超时后迟到响应 | 不重复完成，不关闭正常连接，不遗留 pending |
| 响应与超时并发竞态 | 每个请求仅有一个最终状态，定时任务/Map 无残留 |
| 慢服务队列满 | 有明确过载错误，实际队列不超过配置 |
| 慢服务过载时调用快服务 | 快服务仍可处理；量化效果由压测确定 |
| 服务身份区分 | 相同接口不同 group/version 能定位不同服务配置 |
| 服务端/客户端关闭 | 不再接收新提交，线程/连接/定时器按预期结束 |

优先采用 JUnit + Netty EmbeddedChannel/本地测试 Channel 做确定性故障验证，配合 CountDownLatch 控制竞态，不依赖随意 sleep 判断正确性。
ZooKeeper 使用独立测试实例做端到端验证，记录数据清理方式。已有测试可能依赖外部注册中心，应在构建时区分单元测试与集成测试，不能把跳过测试的 package 当作测试通过。

## 8. 公平实验设计

必须保存三个阶段，避免把修 bug 的收益冒充线程隔离收益：

| 版本 | 内容 | 用途 |
| --- | --- | --- |
| V0 | 原始 2025 快照 | 展示已确认的功能缺口，不用于隔离性能主结论 |
| V1 | 共同可靠性修复 + SHARED 有界执行器 | 性能基线 |
| V2 | 与 V1 相同，仅切换 ISOLATED | 隔离实验 |

V1/V2 的 Handler 分发方式完全一致；否则原始的 Channel 串行执行会成为混杂因素。

初始参数（用于探索，不是保证结果）：
- V1：16 个业务线程、共享队列 200。
- V2：快服务 8 线程/队列 100，慢服务 8 线程/队列 100。
- 相同 JVM、依赖、CPU/内存限制、I/O 线程数、消息格式、Hessian + GZIP 配置和日志级别。
- FastService 返回固定小对象；SlowService 初始模拟 200 ms 阻塞；请求携带唯一 ID，核对返回 ID。
- 压测参数记录包括 payload 大小、连接数、在途上限、请求时限和采样周期。
- 关闭逐请求 INFO 日志，两组使用相同日志设置；保留 WARN/ERROR 与必要聚合指标。

负载：
1. 全部健康：快服务单独运行，再做混合但未饱和场景，测隔离开销和资源闲置代价。
2. 慢服务过载：快服务保持固定到达率，逐步增加慢服务流量。
3. 恢复：停止过载输入，观察拒绝率和排队延迟恢复。
4. 单连接与多连接分别验证：不能只用“一条连接”证明普遍隔离收益。
5. 两个服务互换角色再验证，减少偏向某一服务分配的解释。

慢服务理论容量可先用线程数 / 平均服务时长估算，如 8 / 0.2 ≈ 40 req/s；仅用于选择压力档位，真实结果受协议、调度及响应开销影响。
探索阶段后，正式实验冻结相同负载参数，不分别给两组挑最优流量。
建议预热 30 秒、采样 120 秒，每组至少 3 次；根据机器表现调整，记录实际采用值。初期可用更短冒烟验证。

负载发生器：
- 正常调用用 JMH 适配器；过载与故障用独立 Java 场景运行器，不用默认 HTTP JMeter 请求私有 RPC；详见第 12 节。
- 快慢流量用独立调度计划；按预定到达率提交异步调用，设置客户端最大在途量。
- 保存计划发出、实际发出、未发出、成功、过载、超时、传输失败及业务失败数量。
- 除实际发送到收到响应的延迟，也记录调度延迟，避免客户端拥塞/闭环负载隐藏服务端长尾。
- 明确报告负载发生器无法按计划发送的情况；若生成器饱和，不作服务端性能结论。
- 若同机测试，分别限制/监测服务端与压测端资源，注明结果不代表跨机生产吞吐。

## 9. 指标、公式与验收

核心性能指标：
- 快服务成功 QPS、成功请求 P95/P99、成功率。
- 慢服务成功 QPS、过载拒绝率、超时率。
- 每服务活跃线程、队列长度峰值、拒绝次数、执行耗时。
- 服务端 CPU、堆内存、GC 与客户端资源占用。
- 故障恢复时间：结束过载输入后，快服务连续三个采样窗口恢复到事先定义的正常阈值所需时间。

可靠性指标：
- 测试涉及的故障种类、请求总数与持续时间。
- pending 峰值与结束后残留。
- response / timeout / cancel / transport failure 的最终状态分布。
- 连接 A 失败后，连接 B 的错误请求数。
- 完成后待清理的超时任务/关闭监听器；关闭后仍存活的项目线程。

公式：
- 成功 QPS = 采样窗口内成功完成请求数 / 窗口秒数。
- P95 降幅 = (V1 P95 − V2 P95) / V1 P95 × 100%。
- 成功率提升用百分点表达，不能把百分点写成相对百分比。
- 拒绝请求不得计为成功吞吐；成功延迟、失败延迟分开报告。
- 不平均每秒 P95 来冒充全窗口 P95；合并原始延迟或兼容 Histogram 后计算。
- “0 残留/0 错配”必须附带测试范围，不能宣称所有条件都绝对不会发生。

验收：
1. 正常 RPC 功能没有退化。
2. 所有已覆盖故障请求都在约定时间内终结，pending 最终恢复基线。
3. 队列长度保持有界，过载响应可以被客户端识别。
4. V1/V2 相同负载下完成三次实验，提供原始数据和成功率。
5. 即使吞吐没有提升，也如实报告隔离收益、健康场景开销和静态划分造成的闲置代价。
6. 若没有效果，先检查线程和流量瓶颈；不能为了数字故意破坏基线。

## 10. 最终交付物与四条简历结构

仓库交付：
- 原始基座 commit、来源和许可证。
- 环境/启动/测试/压测命令。
- 服务隔离配置示例、快慢/故障服务、压测入口。
- 原始 CSV/JSON、汇总报告、CPU/堆/队列曲线。
- 设计说明：线程模型、请求终结路径、过载响应、关闭顺序、已知边界。
- 基线与改造分支/提交对应表，确保他人能复现实验。

四条简历建议：
1. 网络通信与透明调用：基于原有 Netty 协议、代理及 Spring 集成，完成接入与协议场景验证。
2. 服务注册发现与扩展：基于原有 ZooKeeper、SPI、负载均衡，验证多实例调用和服务身份配置。
3. 请求可靠性：参考 2026 上游回补超时、断连与失败清理，提供故障数量、请求量和 pending 残留证据。
4. 服务隔离：参考 Dubbo 新增服务级有界执行器，对比正常服务成功吞吐和 P95，数字由实测填写。

前两条是项目能力，后两条是自己的主要工作。“参考上游回补”与“自己新增”在 README 中明确区分；简历可以用独立项目名，简介注明基于 guide-rpc-framework。

项目时间填写实际改造时间，不把 2026 年的个人工作写回 2025 年。

## 11. 首日任务

1. 在自己的开发环境固定 e2f0dd1，保存 java/mvn 版本。
2. 构建并启动示例，记录一次正常请求和一次双客户端连接。
3. 给共享 Handler、无响应和建连失败分别写最小复现。
4. 先修双连接/null/逐请求错误，保留失败到通过的证据。
5. 再实现客户端超时与 pending 清理；通过后开始线程隔离。

到这里就能确认底座是否可用。若阶段 0 的问题超出一天仍无法控制，可改用 2026 基座并只做服务隔离；不要为了“旧版本”无限消耗时间。

## 12. 评测体系：JMH 适配与场景评测

补充日期：2026-10-10。方案：guide-rpc-framework + 参考 Apache dubbo-benchmark 的 JMH 结构 + 独立故障/隔离运行器。本节为待实现计划，尚未实现或运行适配器。

### 12.1 工具分工

| 层次 | 工具 | 验证内容 |
| --- | --- | --- |
| 正确性 | JUnit、EmbeddedChannel、本地 TCP 集成测试 | 协议、结果、异常与生命周期 |
| 正常调用基准 | JMH + GuideRpcAdapter | 健康场景成功吞吐、调用耗时、采样延迟 |
| 隔离与故障 | 固定到达率异步 ScenarioRunner | 过载、快慢服务相互影响、断连、超时和恢复 |
| 内部组件（可选） | JMH | 序列化、编解码耗时及分配 |
| 结果汇总 | Python | 原始 JSON/CSV、重复运行统计、A/B 报告 |

同步 JMH 基准通常是闭环负载：服务变慢后，客户端自然减少新请求。SampleTime 可报告其采样方法下的 P95/P99，但不能代表固定流量下的完整长尾。
隔离主结论由固定到达率场景评测给出，同时记录调度延迟、未发出请求和错误类型。JMH 用来测健康场景与改造开销。

### 12.2 参考实现与适配边界

参考 apache/dubbo-benchmark，已下载检查提交：
`0641cb0877d11da7e2506205d7174f58e549dcfb`，Git 作者时间 2024-09-05。

重点阅读：
- `benchmark-base/.../rpc/AbstractClient.java`：公共业务调用。
- `client-base/.../ClientSimple.java`：JMH 基准与执行入口。
- `client-base/.../ClientHelper.java`：预热、测量、结果格式。
- 根 `benchmark.sh` 和 `pom.xml`：构建、服务地址与运行方式。

借鉴公共接口/数据对象、RPC 适配、服务端与客户端分进程、JMH 预热和 JSON 输出。自己的私有协议需要 GuideRpcAdapter，不能直接接受 Dubbo 客户端请求。
不要直接继承官方整个多模块工程。已检查官方 POM 使用 Dubbo 3.2.11、JMH 1.21，ClientSimple 固定 32 线程和 1 个 fork，profiling 脚本含旧 JVM 参数。自己的参数必须实际生效，Profiling 根据实际 JDK 单独配置。
拷贝源码需保留许可和来源；仅借鉴结构也在 README 标注参考 SHA。
以上是源码调查结果，官方 benchmark 和本项目适配器均未在本次跑通。

### 12.3 新增模块与文件

| 模块/文件 | 内容 |
| --- | --- |
| rpc-benchmark-api | BenchmarkService、EchoRequest/Response、User/Page |
| rpc-benchmark-server | 基准服务、共享/隔离配置加载、线程池统计 |
| rpc-benchmark-client | RPC 适配、JMH、场景运行器 |
| adapter/RpcBenchmarkAdapter.java | connect、同步/异步 invoke、close |
| adapter/GuideRpcAdapter.java | 接入自己的代理和 Netty 异步入口 |
| jmh/RpcCallBenchmark.java | 正常调用 Throughput/SampleTime |
| scenario/ScenarioRunner.java | 快慢服务固定到达率、分阶段负载、分类统计 |
| scenario/FaultController.java | 慢响应、错误、控制连接故障 |
| scripts/benchmark.sh | 参数、构建、启动与输出目录 |
| scripts/summarize.py | 读取 JSON/CSV 并生成汇总报告 |
| configs/shared.properties、isolated.properties | 同资源 A/B 参数 |

第一轮只做 GuideRpcAdapter，DubboAdapter 可选。先复用评测结构，再增加外部框架对比，不把“比 Dubbo 快”设成项目目标。

### 12.4 JMH 实现与配置

1. 固定与实际 JDK 兼容、构建确认的 JMH 版本，core 与注解处理器版本一致，禁止动态 latest。
2. 配置注解处理器、可执行 benchmark JAR，先用列举命令确认基准被发现。
3. `@Setup(Level.Trial)` 创建客户端、连接、代理并做一次正确性调用，初始化不计入稳态调用。
4. `@State(Scope.Benchmark)` 只保存线程安全客户端和只读配置；可变 DTO/计数器用 `Scope.Thread`。
5. `@TearDown(Level.Trial)` 关闭客户端、连接和计时资源。
6. `@Benchmark` 返回结果或用 Blackhole 消费，并验证响应内容。一次 invocation 必须对应一次完成的 RPC，不能只测 Future 提交速度。
7. 正常基准出现过载、超时或传输失败，标记运行失败并保留日志，不吞异常后算成功吞吐。
8. Throughput 与 SampleTime 分开跑；AverageTime 可选。默认复用连接，冷启动另外测。
9. 正式起始配置：预热 3 × 10 秒、测量 3 × 20 秒、3 个 fork；先做短冒烟。每个 fork 的服务端按相同方式重启或重置并预热。
10. 实际生效的线程数、fork、模式、依赖和序列化配置写入 manifest，不能只记录传入参数。

最小矩阵：
- SHARED / ISOLATED。
- 客户端线程数 16 / 64。
- 请求业务 payload 128 B / 1 KiB。
- Echo 先完成，GetUser 为第二个对象场景。
- 连接复用，记录实际连接数。需要多个独立连接时，确认底座单例不会让客户端实例意外共用 Channel。

数据用固定种子的可复现内容，记录压缩前后大小，避免全零 payload 的高度压缩偏差。
默认统一序列化与压缩配置，不做协议、序列化、并发、payload 全排列。内部组件基准和序列化对比可选。

拟脚本接口（待实现）：
```bash
./scripts/benchmark.sh jmh --mode shared --threads 16 --payload 128 --metric throughput
./scripts/benchmark.sh jmh --mode isolated --threads 16 --payload 128 --metric throughput
./scripts/benchmark.sh jmh --mode shared --threads 16 --payload 128 --metric sample
./scripts/benchmark.sh jmh --mode isolated --threads 16 --payload 128 --metric sample
```

### 12.5 服务隔离场景

通过同一构建产物切换配置，使用第 8 节相同线程总量、队列总容量。
初始阶段：预热 30 秒 → 健康 60 秒 → 慢服务过载 120 秒 → 恢复 60 秒。
先探索负载，正式 A/B 前冻结参数。快服务到达率选择低于两组健康容量较小值的约 50%；慢服务先从估算容量的 0.5、1、2 倍探索，至少保留未饱和和明显过载两档。

- 快慢流量独立调度，提交和响应通过异步机制完成。
- 调度只提交，客户端提交队列与在途量有界。
- 用 System.nanoTime 测相对时长；墙钟用于日志关联。
- 分服务、分阶段记录计划/实际发送、调度延迟、成功/拒绝/超时/传输错误及终结延迟。
- 达到客户端在途限制而未发出的请求单列 client_not_sent，不计为服务端拒绝。
- 每秒输出服务端队列、活跃线程、pending 等聚合指标，两组日志设置相同。
- 客户端请求结束后，等待有明确上限的清理窗口并检查 pending。
- 客户端超时不等于服务端停止业务；恢复观测需要等服务端任务排空或达到测试上限。
- 单连接、多连接、快慢角色互换分别验证，避免只用特殊连接分配展示收益。

拟接口（待实现）：
```bash
./scripts/benchmark.sh scenario --mode shared --scenario slow-overload
./scripts/benchmark.sh scenario --mode isolated --scenario slow-overload
```

### 12.6 故障评测矩阵

| 故障 | 注入方式 | 验收 |
| --- | --- | --- |
| 不响应/超时后释放 | 测试服务可控阻塞 | 超时及时终结、pending 清理、迟到响应不破坏连接 |
| 建连失败 | 本地未监听端口 | 无无限等待；此场景是连接拒绝，不是网络黑洞 |
| 写失败 | 测试 Channel/控制关闭时机 | Future 失败并清理 |
| 响应与超时竞态 | 同步屏障控制先后 | 只有一个最终状态 |
| 单连接断开 | 只关闭 A 连接 | A 在途失败，B 继续正常 |
| 业务异常/null | 测试接口 | 语义正确，下一次正常请求仍成功 |
| 过载 | 填满有界队列 | 明确错误，队列不超容量 |
| 客户端关闭 | 有在途请求时 close | 等待终结，计时器/线程结束 |

先做确定性回归，再做 N 次重复稳定性验证，记录实际 N、时长和负载。
输出 pass/fail 及真实断言，不能用只有性能数字的报告证明可靠性。
pending=0 只证明请求上下文未残留，不等于证明所有堆对象都没有泄漏。

### 12.7 数据输出与汇总

每次实验保存：
- `manifest.json`：项目/基座/参考 SHA、JDK/JMH/Maven、依赖、机器、JVM 参数、配置、连接数、超时与负载。
- `jmh.json`：原生 JMH 结果，含 fork/iteration 数据，区分运行模式。
- `scenario.csv`：服务、阶段、计划/实际发出/未发出、状态分类、成功延迟和在途量。
- `metrics.csv`：CPU、堆、GC、线程池、队列及 pending 随时间变化。
- `fault-results.json`：注入条件、请求量、断言和清理结果。
- `summary.md`：逐次结果、跨运行汇总、异常和边界。

统计要求：
1. JMH ops/ms 转 req/s 乘 1000，前提是每 invocation 对应一次成功 RPC。
2. 以 requestId 分类请求，阶段结束未完成的请求继续结算，不丢掉长尾。
3. 阶段成功率按该阶段发出的请求 cohort 观察至终结；吞吐按完成时间所属窗口计算，两者口径分别说明。
4. 每次先计算自己的 QPS/成功率/P95/P99，再报告重复运行中位数和范围，保留逐次数据。
5. 不平均 P95 称为合并 P95；合并分位数需原始样本或兼容直方图。
6. V1/V2 交替运行或用固定种子随机排序，保留同样预热规则。
7. 生成器饱和、未按计划发流量的运行，不用于服务端性能结论。
8. 图表先做三张：快服务 P95、成功 QPS、队列/pending 随时间变化。
9. 外部 Dubbo 对比必须对齐语义、消息、压缩、序列化和资源，不据单场景宣称全面优于 Dubbo。

### 12.8 最小交付与时间安排

第一轮必须有：
- GuideRpcAdapter + Echo JMH。
- Throughput/SampleTime、可生效的并发/payload 参数、JSON 输出。
- SHARED/ISOLATED 快慢服务场景。
- 超时、单连接断开、迟到响应与过载的故障报告。
- 从原始结果自动生成 A/B 汇总表，包含错误率与未发出请求。

第二轮可选：GetUser/集合、序列化微基准、DubboAdapter、真实阻塞 I/O、JFR/async-profiler、CI 回归。

比原计划增加约 1～2 个有效工作日，总体按 8～12 天安排。
JMH 适配、故障断言通过、隔离 A/B 有原始数据是三个不同验收结果。
评测支撑原来四条简历，不另加第五条；可描述“基于 JMH 建立可复现 RPC 基准，结合混合负载与故障注入验证改造效果”，数字由实际测量填写。

## 13. 调研来源

已通过本地 Git 完整历史和固定提交源码检查：
- [2025 基座](https://github.com/Snailclimb/guide-rpc-framework/tree/e2f0dd1be07770da08f38d91a8649e4cdf562ac8)
- [2026 健壮性提交](https://github.com/Snailclimb/guide-rpc-framework/commit/6e6f3132b3a34843b1e7a1baeec2be12c5dfa7cc)
- [2026 异步与错误提交](https://github.com/Snailclimb/guide-rpc-framework/commit/704a4a82240d02fdcf89192c6d185cd07594b9f3)
- [当前 README](https://github.com/Snailclimb/guide-rpc-framework/blob/master/README.md)
- [Dubbo 线程池隔离](https://dubbo.apache.org/en/docs3-v2/java-sdk/advanced-features-and-usage/service/isolation-executor/)
- [SOFABolt 官方仓库](https://github.com/sofastack/sofa-bolt)
- [Apache Dubbo 官方仓库](https://github.com/apache/dubbo)
- [Dubbo Benchmark 固定参考提交](https://github.com/apache/dubbo-benchmark/tree/0641cb0877d11da7e2506205d7174f58e549dcfb)
- [SOFARPC Benchmark](https://github.com/sofastack/sofa-rpc-benchmark)

这份报告是选型与执行计划，不是已完成实现或已取得性能提升的证明。

