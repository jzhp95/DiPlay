# arm64与后续优化核对（2026-10-30）

用户已确认目标星瑞2026昆仑/Flyme Auto Android9车机ABI为arm64-v8a。本轮落地明确的ABI编译/打包约束，不更改Speex浮点/FFT算法、TLS安全语义、媒体内存所有权或会话线程模型。

## 本轮落实

- shared的ndk.abiFilters仅arm64-v8a，避免编译另外三套本项目JNI库。
- mobile再次限制arm64-v8a，过滤来自依赖AAR的其他ABI原生库。
- CI检查真实APK条目与ELF64/AArch64头，并要求热点、Speex及I2C三库存在；避免只改目录名或漏库却通过。
- 更新AGENTS和构建文档，版本仍0.2.26/45，API下限28。
- 改动前APK 64,313,955字节；4 ABI共16个.so，非arm64三组共722,780字节（约706KiB，占APK约1.12%）。不能据此宣称整体APK缩小40%-60%。实际改动后结果见下方验证记录。

## 其他建议的判定

| 建议 | 判定/下一步 |
|---|---|
| USB/NCM全面零拷贝、对象池 | USBMUX缓冲已改几何扩容和消费游标；NCM解析已有offset/length入口。交付给异步消费者的payload仍需独立所有权，不能为零拷贝目标把已复用内存交出去；进一步池化应先测分配热点并设计借还/退休屏障 |
| 把Activity改Fragment/Compose/MVI | 页面仍较大，有可维护性收益；服务会话所有权已抽离。后续可按设置卡片/报告共享/授权UI逐块提取，不以代码行数推导必须重写UI框架 |
| 协程取代所有线程/轮询 | USB/Native阻塞调用仍需合适执行器；delay不自动取消系统调用。需保留资源实际退出和停止截止语义，不进行无测量的全局替换 |
| JCE默认算法/别名回退 | getDefaultAlgorithm确实是可移植入口，但未取得目标ROM缺少PKIX的证据。后续仅对算法查找失败回退，不应吞掉密钥、证书或初始化错误，更不能改变仅用于本地USB的TLS信任边界 |
| 缓存热点反射 | 主要位于配置控制路径，未证实为逐帧热点。API28目标不能直接依赖API30的TetheringManager；需要可用性与权限降级测试再决定缓存边界 |
| -O3、NEON或替换FFT | ARM64支持NEON，NDK默认启用；USE_KISS_FFT并不能证明编译产物完全没有向量化。需CPU分析、汇编与回声质量/数值回归后选择，不能仅改-O3便宣称降低发热 |
| 64位APP减少Zygote32常驻 | 本应用不能关闭系统为其他32位应用保留的Zygote32，因此不作系统内存下降承诺 |
| 一律Bitmap.HARDWARE/DirectByteBuffer | CPU采样、截图、软件YUV和诊断读回需要可访问像素；DirectBuffer也有Native分配和生命周期成本，不能全面替换 |
| R8/Native裁剪 | ABI精简是本轮确定项。反射、Binder、JNI及资源间接引用需要保持规则与打包回归，不能未经验证直接开启全量收缩 |

## 官方依据

- ABI及ELF：https://developer.android.com/ndk/guides/abis
- ARM64/NEON默认支持：https://developer.android.com/ndk/guides/cpu-arm-neon
- KeyManagerFactory默认算法：https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/KeyManagerFactory.html

本记录中的候选不是已实施或已证实性能收益；实际收益以本轮APK对比和后续实车测量为准。

## 实测结果与门禁

| 指标 | 改动前 | 改动后 |
|---|---:|---:|
| APK字节数 | 64,313,955 | 61,882,582 |
| 原生库字节数 | 983,508 | 260,728 |
| APK中的ABI | 4 | 仅arm64-v8a |
| APK中的.so数量 | 16 | 4 |

APK减少2,431,373字节（3.78%）；原生库部分减少722,780字节（73.49%）。这是当前包的实际对比，不代表每版相同比例，也没有进行CPU/发热或实车帧率测量。

完整CI命令退出码0；Flyme10/shared1376/common1501项零失败（shared原有1项环境跳过），单测复用刚通过的同实现结果。lint为0错误/5警告，Manifest和签名校验通过。NDK日志仅配置/构建arm64-v8a；APK检查器先拒绝旧四ABI包，再核对新包中所有4个.so的ELF64/AArch64头和3个必需JNI库。

证据目录：`../artifacts/diplay-architecture/20261030-arm64-only-be551988/`（仓库上一级），包括comparison.json、apk-abi.json、原始命令/日志、XML、lint与本地测试APK。该APK包含按原构建规则提供的本地认证输入，公共源码快照不包含私密输入；默认debug签名不等于车机平台签名，未安装或发布。

本轮未启用-O3/fast-math，未替换KISS FFT，未引入新协程/Compose依赖，未修改TLS信任或热点反射路径。上述候选继续按可测收益和API28执行路径单独评估。

补充说明：整包差值包含ZIP布局/对齐及其他打包差异；可以直接对应到被移除原生条目的净字节数为722,780，不能把全部整包差值都当作已隔离测量的ABI优化收益。新增的ChromeOsAbiSupport警告来自不再支持x86_64，与已确认的车机目标一致，保持记录而不屏蔽。其他4条既有warning未在本轮改动其行为。
