# 固定基线与分支

来源：Snailclimb/guide-rpc-framework；保留完整上游历史及根目录 Apache-2.0 LICENSE。

- `baseline/2025` 和 `main`：`e2f0dd1be07770da08f38d91a8649e4cdf562ac8`，原始 V0，不在此分支修改。
- `project/rpc-isolation`：按构建、逐请求响应、生命周期、执行器、测试、评测拆分提交。
- `upstream`：原作者仓库，只读取；`origin`：Jojen6324/isolate-rpc。
- V1/V2 使用相同构建，通过 shared/isolated 配置切换，不分别改代码。
- 不整体 cherry-pick 2026 提交，不自动重试业务请求。

## 环境

Temurin 8u462-b08、Maven 3.9.9、ZooKeeper 3.5.8。下载保留 TLS 校验，解压前校验发布者校验和。工具存放于 `/workspace/toolchains`，Maven 缓存位于 `/workspace/.m2/repository`。Maven 使用环境代理及系统 Java 信任库。

```bash
./scripts/setup-dev.sh
./scripts/zk.sh start
./scripts/mvn.sh test
```

## V0 实测（2026-10-10）

JDK 8 下所有 6 个 reactor 项目 `-DskipTests install` 成功。ZooKeeper 启动后第一客户端通过 `-ea` 断言并返回 11 次预期结果；第二个进程连接触发 `NettyRpcServerHandler is not a @Sharable handler`，证明多连接缺陷。旧客户端调用后不退出，使用外部 timeout 终结，不算正常退出通过。

原 POM 测试运行器不能可靠发现 JUnit 5：显式 Surefire 3.2.5 仍自动选择 JUnit4，只执行 common 的两个测试，simple 模块执行 0 个测试。因此构建成功不代表原测试全部通过；开发分支需显式启用 JUnit Platform。
