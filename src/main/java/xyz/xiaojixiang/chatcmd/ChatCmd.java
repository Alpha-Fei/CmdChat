package xyz.xiaojixiang.chatcmd;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ChatCmd extends JavaPlugin implements Listener {

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(?:\\[(\\d+\\.?\\d*)]\\s*)?(?:\\[(\\d+)%]\\s*)?(.+)$");
    private final List<CommandGroup> commandGroups = new ArrayList<>();
    private final Map<UUID, String> playerLastMessages = new ConcurrentHashMap<>();
    private final String configHeader = "# 作者：小吉祥\n# 修复配置删除后指令仍然运行的问题";
    private boolean hasPlaceholderAPI = false;
    private ChatCmdExpansion expansion;
    private boolean messageCommandEnabled = false;
    private List<String> messagePlayerCommands = new ArrayList<>();
    private List<String> messageConsoleCommands = new ArrayList<>();
    private List<String> messageOpCommands = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        // 检测PlaceholderAPI
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (hasPlaceholderAPI) {
            getLogger().info("PlaceholderAPI支持已启用");
            expansion = new ChatCmdExpansion(this);
            if (expansion.canRegister()) {
                expansion.register();
                getLogger().info("已注册PlaceholderAPI扩展");
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        ReloadCommand reloadCommand = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("ccmd")).setExecutor(reloadCommand);
        Objects.requireNonNull(getCommand("ccmd")).setTabCompleter(reloadCommand);

        getLogger().info("ChatCmd 1.4.1 已加载！");
    }

    @Override
    public void onDisable() {
        if (hasPlaceholderAPI && expansion != null) {
            expansion.unregister();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadCommandGroups();
        loadMessageCommandConfig();
    }

    private void loadCommandGroups() {
        commandGroups.clear();
        FileConfiguration config = getConfig();

        List<Map<?, ?>> groups = config.getMapList("groups");
        for (Map<?, ?> group : groups) {
            CommandGroup cmdGroup = new CommandGroup();
            cmdGroup.keywords = (List<String>) group.get("keywords");
            cmdGroup.permission = group.containsKey("permission") ? (String) group.get("permission") : null;
            cmdGroup.playerCommands = toStringList(group.get("player"));
            cmdGroup.consoleCommands = toStringList(group.get("console"));
            cmdGroup.opCommands = toStringList(group.get("op"));
            commandGroups.add(cmdGroup);
        }
    }

    private void loadMessageCommandConfig() {
        FileConfiguration config = getConfig();
        
        // 重置消息指令列表
        messagePlayerCommands = new ArrayList<>();
        messageConsoleCommands = new ArrayList<>();
        messageOpCommands = new ArrayList<>();
        
        // 检查MessageCommand部分是否存在
        if (config.isConfigurationSection("MessageCommand")) {
            ConfigurationSection section = config.getConfigurationSection("MessageCommand");
            messageCommandEnabled = section.getBoolean("Enabled", false);
            
            // 检查并加载每个指令列表（修复BUG的关键）
            if (section.isList("player")) {
                messagePlayerCommands = toStringList(section.get("player"));
            }
            if (section.isList("console")) {
                messageConsoleCommands = toStringList(section.get("console"));
            }
            if (section.isList("op")) {
                messageOpCommands = toStringList(section.get("op"));
            }
        } else {
            messageCommandEnabled = false;
        }
    }

    private List<String> toStringList(Object obj) {
        if (obj instanceof List) {
            return ((List<?>) obj).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        
        // 更新玩家最后一条消息
        playerLastMessages.put(player.getUniqueId(), message);
        
        // 处理全局消息检测
        if (messageCommandEnabled) {
            Bukkit.getScheduler().runTask(this, () -> {
                // 仅在有指令时执行（修复BUG）
                if (!messagePlayerCommands.isEmpty()) {
                    executeCommands(messagePlayerCommands, player, message, "", "player");
                }
                if (!messageConsoleCommands.isEmpty()) {
                    executeCommands(messageConsoleCommands, player, message, "", "console");
                }
                if (!messageOpCommands.isEmpty()) {
                    executeCommands(messageOpCommands, player, message, "", "op");
                }
            });
        }
        
        // 处理关键词检测
        for (CommandGroup group : commandGroups) {
            if (group.permission != null && !player.hasPermission(group.permission)) {
                continue;
            }

            group.keywords.stream()
                .filter(message::contains)
                .findFirst()
                .ifPresent(keyword -> Bukkit.getScheduler().runTask(this, () -> {
                    executeCommands(group.playerCommands, player, message, keyword, "player");
                    executeCommands(group.consoleCommands, player, message, keyword, "console");
                    executeCommands(group.opCommands, player, message, keyword, "op");
                }));
        }
    }

    private void executeCommands(List<String> commands, Player player, String message, String keyword, String executorType) {
        for (String rawCmd : commands) {
            // 解析命令参数（延迟和概率）
            Matcher matcher = COMMAND_PATTERN.matcher(rawCmd);
            if (!matcher.find()) {
                getLogger().warning("无效的命令格式: " + rawCmd);
                continue;
            }
            
            double delaySeconds = 0.5; // 默认0.5秒
            double chance = 1.0; // 默认100%概率
            String actualCmd = matcher.group(3);
            
            // 解析延迟
            if (matcher.group(1) != null) {
                try {
                    delaySeconds = Double.parseDouble(matcher.group(1));
                } catch (NumberFormatException e) {
                    getLogger().warning("无效的延迟配置: " + rawCmd);
                }
            }
            
            // 解析概率
            if (matcher.group(2) != null) {
                try {
                    double percent = Double.parseDouble(matcher.group(2));
                    chance = Math.min(100, Math.max(0, percent)) / 100.0;
                } catch (NumberFormatException e) {
                    getLogger().warning("无效的概率配置: " + rawCmd);
                }
            }
            
            // 概率检查
            if (Math.random() >= chance) {
                continue;
            }

            // 处理占位符
            String processedCmd = actualCmd
                .replace("%player%", player.getName())
                .replace("%message%", message)
                .replace("%keyword%", keyword);

            if (hasPlaceholderAPI) {
                processedCmd = PlaceholderAPI.setPlaceholders(player, processedCmd);
            }

            // 创建最终临时变量
            final String finalCommand = processedCmd;
            final Player finalPlayer = player;
            final String finalExecutorType = executorType;

            // 计算延迟刻数
            long delayTicks = (long) (delaySeconds * 20);

            // 延迟执行逻辑
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    switch (finalExecutorType.toLowerCase()) {
                        case "console":
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                            break;
                        case "op":
                            boolean wasOp = finalPlayer.isOp();
                            try {
                                finalPlayer.setOp(true);
                                finalPlayer.performCommand(finalCommand);
                            } finally {
                                if (!wasOp) finalPlayer.setOp(false);
                            }
                            break;
                        default:
                            finalPlayer.performCommand(finalCommand);
                    }
                } catch (Exception e) {
                    getLogger().warning("执行命令时出错: " + finalCommand);
                    e.printStackTrace();
                }
            }, delayTicks);
        }
    }

    // 内部类定义
    private static class CommandGroup {
        List<String> keywords;
        String permission;
        List<String> playerCommands;
        List<String> consoleCommands;
        List<String> opCommands;
    }

    // 命令处理类（含Tab补全）
    private static class ReloadCommand implements CommandExecutor, TabCompleter {
        private final ChatCmd plugin;

        public ReloadCommand(ChatCmd plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("chatcmd.reload")) {
                    sender.sendMessage("§c你没有权限执行此命令！");
                    return true;
                }
                plugin.reloadPluginConfig();
                sender.sendMessage("§a配置已重新加载！");
                return true;
            }
            return false;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1 && sender.hasPermission("chatcmd.reload")) {
                return Collections.singletonList("reload");
            }
            return Collections.emptyList();
        }
    }

    @Override
    public void saveDefaultConfig() {
        super.saveDefaultConfig();
        FileConfiguration config = getConfig();
        config.options().header(configHeader);
        config.options().copyHeader(true);
        saveConfig();
    }
    
    // PlaceholderAPI扩展实现
    public static class ChatCmdExpansion extends PlaceholderExpansion {
        private final ChatCmd plugin;
        
        public ChatCmdExpansion(ChatCmd plugin) {
            this.plugin = plugin;
        }
        
        @Override
        public String getIdentifier() {
            return "chatcmd";
        }
        
        @Override
        public String getAuthor() {
            return "小吉祥";
        }
        
        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }
        
        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) {
                return "";
            }
            
            if ("message".equalsIgnoreCase(identifier)) {
                return plugin.playerLastMessages.getOrDefault(player.getUniqueId(), "");
            }
            
            return null;
        }
    }
}