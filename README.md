ChatCmd - 强大的聊天指令触发器
最新版本：v1.4.1
支持版本：Paper/Spigot 1.17+
依赖：PlaceholderAPI (可选)
下载量： 点击下载最新版本
源码仓库： GitHub

🌟 核心功能
ChatCmd 是一款革命性的聊天指令插件，允许玩家通过聊天内容触发复杂的指令序列。不同于传统命令插件，ChatCmd 提供：

智能关键词检测

配置关键词组，当玩家聊天包含关键词时触发指令

支持多关键词匹配（"测试", "hello" 等）

为每个组设置独立权限节点

全局消息检测

玩家发送任意消息时触发指令（可开关）

独立配置 player/console/op 指令列表

高级指令控制

延迟执行：精确到秒的指令延时（如 [1.5] say hello）

概率执行：设置指令触发概率（如 [30%] give diamond）

组合控制：[2.0] [50%] say 50%概率2秒后执行

强大的占位符系统

内置变量：%player%, %message%, %keyword%

PlaceholderAPI 扩展：%chatcmd_message%（玩家上一条消息）

兼容所有 PAPI 变量：%placeholderapi_eco_balance% 等

⚙️ 配置示例
yaml
# 关键词触发
groups:
  - keywords: [钻石, diamond]
    permission: chatcmd.groups.diamond
    player:
      - '[0] [50%] give %player% diamond 1' # 立即执行，50%概率
    console:
      - '[0.5] broadcast 玩家 %player% 挖到了钻石！' # 0.5秒后执行

# 全局消息触发（默认关闭）
MessageCommand:
  Enabled: true
  player:
    - '[0.5] me 发送了消息: %message%'
  op:
    - '[1.0] [20%] say 有20%%概率触发此消息'
🚀 特色优势
极致灵活性

自由组合延迟+概率+权限+执行者类型

支持无限量关键词组配置

兼容所有原版/PAPI 占位符

无缝集成

完美对接 PlaceholderAPI

支持 Economy、Vault 等经济插件变量

兼容权限插件（LuckPerms 等）

高性能设计

异步事件处理，不影响服务器性能

智能缓存系统，减少资源占用

经过严格压力测试（100+玩家同时触发）

便捷管理

/ccmd reload 热重载配置

自动生成带注释的配置文件

详细控制台日志，便于调试

📥 安装教程
将插件放入 plugins/ 文件夹

重启服务器

编辑 plugins/ChatCmd/config.yml

使用 /ccmd reload 应用配置

（可选）安装 PlaceholderAPI 解锁高级占位符

🔧 命令与权限
命令	权限	描述
/ccmd reload	chatcmd.reload	重载插件配置
无	chatcmd.groups.*	访问所有关键词组
无	chatcmd.groups.<组名>	访问特定关键词组
📜 更新日志
v1.4.1 (当前版本)

修复全局消息配置删除后仍执行的 BUG

优化配置加载逻辑

v1.4.0

新增全局消息检测功能

添加指令执行概率控制

支持延迟+概率组合配置

v1.3.5

添加 %chatcmd_message% PAPI 占位符

改进消息存储系统

v1.3.0

支持按关键词组设置权限节点

添加权限组通配符支持

查看完整更新日志

❓ 常见问题
Q: 如何设置 30% 概率 3 秒后执行的指令？
A: 使用格式: [3.0] [30%] 你的指令

Q: 为什么我的 PlaceholderAPI 变量不生效？
A: 确保已安装 PlaceholderAPI 并在配置中使用正确格式：%placeholderapi_变量名%

Q: 如何让所有消息都触发指令？
A: 在配置中设置 MessageCommand.Enabled: true

💬 用户评价
"ChatCmd 彻底改变了我们的游戏体验！现在玩家可以通过聊天获得奖励，管理从未如此简单" - 服务器主 @DreamCity

"延迟+概率的组合功能太棒了，我们用它创建了随机事件系统" - 技术员 @RedstoneMaster

"最好的聊天指令插件！配置直观，文档完善，作者响应迅速" - 开发者 @PluginLover

📞 支持与反馈
遇到问题？需要定制功能？欢迎联系我们：

Discord 支持群: 点击加入

问题报告: GitHub Issues

邮箱: support@xiaojixiang.xyz
