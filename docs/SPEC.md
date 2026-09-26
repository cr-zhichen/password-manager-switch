# v1.0.0 范围

1. 在 personal/password-manager-switch 初始化原生 Android 项目，简体中文界面，沿用背屏开关的 Material 3 风格。
2. 通过 Shizuku 提供无需 root 的默认密码管理器查看/设置能力，对应 credential_service、credential_service_primary 和 autofill_service。
3. 在 app 中直接从当前用户已安装、可用且声明相应服务的应用中选择，不要求用户输入、不写死密码管理器包名。
4. 区分凭据提供者与自动填充，查看实际系统值，写入后读回，不把读回成功等同于业务创建通行密钥成功。
5. 完成可构建 APK，核心故障场景测试和 Lint。不写 UI 单元测试。
6. 新增备份/恢复及部分失败回滚，以避免三项设置部分成功却报告完成。
7. 按 xiaomi-backscreen-switch 提供应用内自动检查更新、手动检查、跳转下载，独立签名证书/随机密码、GitHub 凭据、CI 自动构建和标签自动发布。
8. MIT 许可证，创建 cr-zhichen/password-manager-switch 公开 GitHub 仓库，推送并发布 v1.0.0。

验收必须区分本地构建、普通 Android 模拟器、GitHub Actions 发布与 HyperOS 真机结果。
