# CMY 启动器（梦之韵Pro）

> 基于 [HMCL](https://github.com/huanghongxun/HMCL) 开源开发版二次开发的 Minecraft 启动器

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

## 项目简介

CMY 启动器（梦之韵Pro）是由梦之韵团队基于 HMCL 开源开发版精心打造的 Minecraft 启动器。在保留 HMCL 核心功能的基础上，新增了云端自动更新、梦之韵整合包一键下载、多实例管理等功能，并针对国内网络环境进行了优化。

## 功能特性

- 🎮 **多实例管理** — 支持多个游戏版本和模组配置独立管理
- ☁️ **云端自动更新** — 启动器启动时自动检测更新，底部状态栏提示
- 📦 **整合包一键下载** — 内置梦之韵整合包下载，支持 HTTP/3 加速
- 🌐 **国内网络优化** — 下载链路走自建代理，避免 DNS 劫持
- 🎨 **自定义主题** — 樱花粉主题，支持背景图自定义
- 🔧 **Java 运行时内置** — 完整包自带 Java 运行时，解压即玩

## 下载

- **官网**：https://www.camzy.tech
- **文档**：https://docs.camzy.tech
- **发布页**：https://github.com/maoxinhe/mzy-pro-source/releases

## 构建

### 环境要求

- JDK 21+
- Gradle 8+

### 编译

```bash
./gradlew :HMCL:shadowJar
```

编译产物位于 `HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar`。

### 运行

```bash
java -jar HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar
```

## 项目结构

```
├── HMCL/              # 启动器主模块
├── HMCLBoot/          # 启动引导模块
├── HMCLCore/          # 核心库模块
├── buildSrc/          # 构建脚本
├── config/            # 项目配置
└── tools/             # 工具脚本
```

## 开源协议

本项目基于 **GNU GPL v3.0** 协议开源，详见 [LICENSE](LICENSE)。

### 第三方依赖

本项目使用了以下开源项目，版权归各自所有者所有：

- **HMCL** — GPL v3.0，Copyright (C) 2013-2025 huangyuhui and HMCL contributors
- **OpenJFX** — GPL v2 with Classpath Exception
- **Gson** — Apache License 2.0
- **JFoenix** — Apache License 2.0

完整列表见启动器「关于」页面。

### 原创资源版权声明

本项目中的原创美术资源（图标、主题素材、UI 设计等）版权归**梦之韵团队**所有，未经授权不得使用。详见 [RESOURCES-LICENSE](RESOURCES-LICENSE)。

## 贡献

欢迎提交 Issue 和 Pull Request。提交代码即表示您同意您的贡献以 GPL v3.0 协议开源。

## 反馈

- **反馈站**：https://fk.camzy.uno
- **官方群组**：见官网

## 致谢

感谢 HMCL 社区及所有开源贡献者的工作。

---

**© 2026 梦之韵团队** | 基于 HMCL 开源开发版二次开发
