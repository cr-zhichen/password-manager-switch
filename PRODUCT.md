# 密码管理器切换

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

HyperOS 用户，在系统不再显示第三方密码管理器开关时，需要查看并切换默认服务。

## Product Purpose

通过 Shizuku 在无需 root 的情况下，查看和修改凭据提供者、首选凭据提供者和自动填充服务。

## Capabilities and Constraints

- 从当前 Android 用户已安装、已启用的合规服务中选择，不输入或写死密码管理器包名。
- 凭据提供者和自动填充服务可独立选择；写入前展示变更，写入后核对，并保存上一次配置以便恢复。
- 不读取、存储或上传密码和通行密钥；联网仅用于检查本项目 GitHub Release 更新。
- Android 14 及以上。HyperOS 的实际凭据创建兼容性需要真机验证。

## Brand Commitments

用户确认名称为「密码管理器切换」，项目为 password-manager-switch，沿用背屏工具的简洁原生 Android 风格。
按背屏项目方式自动检查更新、生成独立签名证书、用 GitHub Secrets 保存解密密码，MIT 开源并自动发布版本。

## Evidence on Hand

用户提供两个 credential_service 设置项的 ADB 解决方式。已有背屏项目提供 Kotlin、Compose Material 3 和 Shizuku UserService 参考。当前没有连接手机。
