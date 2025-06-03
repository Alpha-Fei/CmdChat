package xyz.xiaojixiang.chatcmd;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ChatCmd extends JavaPlugin implements Listener {

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(?:\\[(\\d+\\.?\\d*)]\\s*)?(?:\\[(\\d+)%]\\s*)?(.+)$");
    private final List<CommandGroup> commandGroups = new ArrayList<>();
    private final Map<UUID, String> playerLastMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerMessageCounts = new ConcurrentHashMap<>();
    private final String configHeader = "# 作者：小吉祥\n# 添加管理员更新通知功能";
    private boolean hasPlaceholderAPI = false;
    private ChatCmdExpansion expansion;
    private boolean messageCommandEnabled = false;
    private List<String> messagePlayerCommands = new ArrayList<>();
    private List<String> messageConsoleCommands = new ArrayList<>();
    private List<String> messageOpCommands = new ArrayList<>();
    
    // 数据库相关
    private Connection databaseConnection;
    private boolean statisticsEnabled = false;
    private String databaseType = "SQLite";
    private String dbTable = "chatcmd_statistics";
    
    // 更新检查相关
    private boolean updateCheckEnabled = true;
    private static final String UPDATE_URL = "https://api.github.com/repos/Alpha-Fei/CmdChat/releases/latest";
    private static final String DOWNLOAD_URL = "https://github.com/Alpha-Fei/CmdChat/releases/latest";
    
    // 新增触发开关
    private boolean globalAndKeywords = true; // 默认同时触发
    
    // 更新通知相关
    private String latestVersion = null; // 存储最新版本号
    private boolean updateAvailable = false; // 是否有更新可用

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        // 初始化数据库
        if (statisticsEnabled) {
            if (!initializeDatabase()) {
                getLogger().severe("数据库初始化失败，发言统计功能将不可用！");
            } else {
                getLogger().info("数据库连接成功，发言统计功能已启用");
                loadPlayerMessageCounts();
            }
        }

        // 检测PlaceholderAPI
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (hasPlaceholderAPI) {
            getLogger().info("PlaceholderAPI支持已启用");
            expansion = new ChatCmdExpansion(this);
            if (expansion.canRegister()) {
                expansion.register();
                getLogger().info("已注册PlaceholderAPI扩展");
            } else {
                getLogger().warning("无法注册PlaceholderAPI扩展");
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        ReloadCommand reloadCommand = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("ccmd")).setExecutor(reloadCommand);
        Objects.requireNonNull(getCommand("ccmd")).setTabCompleter(reloadCommand);

        // 检查更新
        if (updateCheckEnabled) {
            checkForUpdates();
        }

        getLogger().info("ChatCmd 1.4.6 已加载！");
    }

    @Override
    public void onDisable() {
        // 关闭数据库连接
        closeDatabase();
        
        // 注销PlaceholderAPI扩展
        if (hasPlaceholderAPI && expansion != null) {
            expansion.unregister();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadMainConfig();
        loadCommandGroups();
        loadMessageCommandConfig();
    }
    
    private void loadMainConfig() {
        FileConfiguration config = getConfig();
        
        // 加载统计配置
        statisticsEnabled = config.getBoolean("Statistics.Enable", false);
        databaseType = config.getString("Statistics.Database.Type", "SQLite");
        dbTable = config.getString("Statistics.Database.Table", "chatcmd_statistics");
        
        // 加载更新检查配置
        updateCheckEnabled = config.getBoolean("UpdateCheck", true);
        
        // 加载同时触发配置
        globalAndKeywords = config.getBoolean("GlobalAndKeywords", true);
        
        // 安全检查数据库连接状态
        if (statisticsEnabled) {
            boolean needReconnect = false;
            
            if (databaseConnection == null) {
                needReconnect = true;
            } else {
                try {
                    needReconnect = databaseConnection.isClosed();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "检查数据库连接状态失败，将重新连接", e);
                    needReconnect = true;
                }
            }
            
            if (needReconnect) {
                initializeDatabase();
            }
        }
    }

    @SuppressWarnings("unchecked")
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
            
            // 检查并加载每个指令列表
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
    
    private boolean initializeDatabase() {
        try {
            if (databaseConnection != null) {
                try {
                    if (!databaseConnection.isClosed()) {
                        databaseConnection.close();
                    }
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "关闭旧数据库连接时出错", e);
                }
            }
            
            if ("MySQL".equalsIgnoreCase(databaseType)) {
                FileConfiguration config = getConfig();
                ConfigurationSection mysqlConfig = config.getConfigurationSection("Statistics.Database.MySQL");
                
                String host = mysqlConfig.getString("Host", "localhost");
                int port = mysqlConfig.getInt("Port", 3306);
                String dbName = mysqlConfig.getString("Database", "minecraft");
                String user = mysqlConfig.getString("Username", "root");
                String pass = mysqlConfig.getString("Password", "");
                
                String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName;
                try {
                    databaseConnection = DriverManager.getConnection(url, user, pass);
                    getLogger().info("已连接MySQL数据库: " + url);
                } catch (SQLException e) {
                    getLogger().log(Level.SEVERE, "MySQL连接失败，尝试使用SQLite回退", e);
                    // 回退到SQLite
                    databaseType = "SQLite";
                    return initializeSQLite();
                }
            } else {
                // SQLite
                return initializeSQLite();
            }
            
            // 创建表
            createStatisticsTable();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "数据库连接失败", e);
            return false;
        }
    }
    
    private boolean initializeSQLite() {
        try {
            String dbPath = getDataFolder().getAbsolutePath() + "/statistics.db";
            databaseConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            getLogger().info("已连接SQLite数据库: " + dbPath);
            createStatisticsTable();
            return true;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "SQLite连接失败", e);
            return false;
        }
    }
    
    private void createStatisticsTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + dbTable + " (" +
                     "uuid VARCHAR(36) PRIMARY KEY, " +
                     "player_name VARCHAR(16) NOT NULL, " +
                     "message_count INT NOT NULL DEFAULT 0)";
        
        try (Statement stmt = databaseConnection.createStatement()) {
            stmt.executeUpdate(sql);
            getLogger().info("数据库表创建成功: " + dbTable);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "创建数据库表失败", e);
            
            // 尝试简化表结构
            try {
                String fallbackSql = "CREATE TABLE IF NOT EXISTS " + dbTable + " (" +
                                      "uuid VARCHAR(36) PRIMARY KEY, " +
                                      "message_count INT)";
                try (Statement fallbackStmt = databaseConnection.createStatement()) {
                    fallbackStmt.executeUpdate(fallbackSql);
                    getLogger().info("使用简化表结构成功");
                }
            } catch (SQLException ex) {
                getLogger().log(Level.SEVERE, "创建简化表结构失败", ex);
            }
        }
    }
    
    private void loadPlayerMessageCounts() {
        if (databaseConnection == null) return;
        
        String sql = "SELECT uuid, message_count FROM " + dbTable;
        
        try (Statement stmt = databaseConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    int count = rs.getInt("message_count");
                    playerMessageCounts.put(uuid, count);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("无效的UUID格式: " + rs.getString("uuid"));
                }
            }
            getLogger().info("已加载 " + playerMessageCounts.size() + " 名玩家的发言统计");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "加载玩家发言统计失败", e);
        }
    }
    
    private void updatePlayerMessageCount(Player player) {
        if (!statisticsEnabled || databaseConnection == null) return;
        
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        
        // 更新内存缓存
        int newCount = playerMessageCounts.getOrDefault(uuid, 0) + 1;
        playerMessageCounts.put(uuid, newCount);
        
        // 异步更新数据库
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if ("MySQL".equals(databaseType)) {
                    updateMySQL(uuid, name, newCount);
                } else {
                    updateSQLite(uuid, name, newCount);
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "更新玩家发言统计失败: " + name, e);
            }
        });
    }
    
    private void updateMySQL(UUID uuid, String name, int newCount) throws SQLException {
        String sql = "INSERT INTO " + dbTable + " (uuid, player_name, message_count) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE message_count = ?, player_name = ?";
        
        try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, name);
            pstmt.setInt(3, newCount);
            pstmt.setInt(4, newCount);
            pstmt.setString(5, name);
            pstmt.executeUpdate();
        }
    }
    
    private void updateSQLite(UUID uuid, String name, int newCount) throws SQLException {
        String sql = "INSERT OR REPLACE INTO " + dbTable + " (uuid, player_name, message_count) " +
                     "VALUES (?, ?, ?)";
        
        try (PreparedStatement pstmt = databaseConnection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, name);
            pstmt.setInt(3, newCount);
            pstmt.executeUpdate();
        }
    }
    
    private void closeDatabase() {
        if (databaseConnection != null) {
            try {
                if (!databaseConnection.isClosed()) {
                    databaseConnection.close();
                    getLogger().info("数据库连接已关闭");
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "关闭数据库连接时出错", e);
            }
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
        
        // 更新发言总数统计
        if (statisticsEnabled) {
            updatePlayerMessageCount(player);
        }
        
        // 处理关键词检测
        boolean keywordTriggered = false;
        for (CommandGroup group : commandGroups) {
            if (group.permission != null && !player.hasPermission(group.permission)) {
                continue;
            }

            Optional<String> matchedKeyword = group.keywords.stream()
                .filter(message::contains)
                .findFirst();

            if (matchedKeyword.isPresent()) {
                keywordTriggered = true;
                String keyword = matchedKeyword.get();
                Bukkit.getScheduler().runTask(this, () -> {
                    executeCommands(group.playerCommands, player, message, keyword, "player");
                    executeCommands(group.consoleCommands, player, message, keyword, "console");
                    executeCommands(group.opCommands, player, message, keyword, "op");
                });
            }
        }
        
        // 处理全局消息检测（根据配置决定是否触发）
        if (messageCommandEnabled) {
            // 当全局和关键词同时触发 或 未触发关键词时 执行全局检测
            if (globalAndKeywords || !keywordTriggered) {
                Bukkit.getScheduler().runTask(this, () -> {
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
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // 检查是否是管理员且有更新可用
        if (player.hasPermission("chatcmd.update") && updateAvailable) {
            sendUpdateNotification(player);
        }
    }
    
    private void sendUpdateNotification(Player player) {
        String currentVersion = getDescription().getVersion();
        
        // 创建可点击的消息
        TextComponent mainMessage = new TextComponent("§e[ChatCmd] §a发现新版本: §b" + latestVersion + " §a(当前: §b" + currentVersion + "§a)");
        mainMessage.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, DOWNLOAD_URL));
        mainMessage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§e点击下载最新版本")));
        
        // 发送消息
        player.spigot().sendMessage(mainMessage);
        
        // 额外发送一条说明消息
        player.sendMessage("§e下载地址: §b" + DOWNLOAD_URL);
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
        
        // 添加统计配置默认值
        config.addDefault("Statistics.Enable", false);
        config.addDefault("Statistics.Database.Type", "SQLite");
        config.addDefault("Statistics.Database.Table", "chatcmd_statistics");
        
        ConfigurationSection mysqlSection = config.createSection("Statistics.Database.MySQL");
        mysqlSection.addDefault("Host", "localhost");
        mysqlSection.addDefault("Port", 3306);
        mysqlSection.addDefault("Database", "minecraft");
        mysqlSection.addDefault("Username", "root");
        mysqlSection.addDefault("Password", "");
        
        // 添加更新检查配置
        config.addDefault("UpdateCheck", true);
        
        // 添加同时触发配置
        config.addDefault("GlobalAndKeywords", true);
        
        config.options().copyDefaults(true);
        saveConfig();
    }
    
    // 更新检查功能
    private void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String currentVersion = getDescription().getVersion();
                String fetchedVersion = getLatestVersion();
                
                if (fetchedVersion == null) {
                    getLogger().info("无法获取最新版本信息");
                    return;
                }
                
                if (isNewerVersion(fetchedVersion, currentVersion)) {
                    // 存储更新信息
                    latestVersion = fetchedVersion;
                    updateAvailable = true;
                    
                    // 控制台输出
                    getLogger().info("§e==============================================");
                    getLogger().info("§e发现新版本: " + latestVersion);
                    getLogger().info("§e当前版本: " + currentVersion);
                    getLogger().info("§e下载地址: " + DOWNLOAD_URL);
                    getLogger().info("§e==============================================");
                    
                    // 通知在线管理员
                    notifyOnlineAdmins();
                } else {
                    getLogger().info("已是最新版本: " + currentVersion);
                    updateAvailable = false;
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "检查更新时出错", e);
            }
        });
    }
    
    private void notifyOnlineAdmins() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("chatcmd.update")) {
                sendUpdateNotification(player);
            }
        }
    }
    
    private String getLatestVersion() {
        try {
            URL url = new URL(UPDATE_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            if (connection.getResponseCode() != 200) {
                getLogger().warning("GitHub API 响应异常: " + connection.getResponseCode());
                return null;
            }
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                
                JSONParser parser = new JSONParser();
                JSONObject json = (JSONObject) parser.parse(response.toString());
                
                return (String) json.get("tag_name");
            }
        } catch (IOException | ParseException e) {
            getLogger().log(Level.WARNING, "获取最新版本失败", e);
            return null;
        }
    }
    
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        // 移除版本号中的非数字字符
        String cleanNew = newVersion.replaceAll("[^\\d.]", "");
        String cleanCurrent = currentVersion.replaceAll("[^\\d.]", "");
        
        String[] newParts = cleanNew.split("\\.");
        String[] currentParts = cleanCurrent.split("\\.");
        
        // 逐级比较版本号
        for (int i = 0; i < Math.max(newParts.length, currentParts.length); i++) {
            int newPart = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            
            if (newPart > currentPart) {
                return true;
            } else if (newPart < currentPart) {
                return false;
            }
        }
        return false;
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
            
            if ("allmessage".equalsIgnoreCase(identifier)) {
                if (!plugin.statisticsEnabled) return "0";
                return String.valueOf(plugin.playerMessageCounts.getOrDefault(player.getUniqueId(), 0));
            }
            
            return null;
        }
    }
    
    // 数据库访问方法（供其他插件使用）
    public int getPlayerMessageCount(UUID uuid) {
        if (!statisticsEnabled) return 0;
        return playerMessageCounts.getOrDefault(uuid, 0);
    }
}
