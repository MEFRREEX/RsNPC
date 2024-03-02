package com.smallaswater.npc;

import cn.lanink.gamecore.utils.Language;
import cn.lanink.gamecore.utils.NukkitTypeUtils;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.SerializedImage;
import com.smallaswater.npc.command.RsNPCCommand;
import com.smallaswater.npc.data.RsNpcConfig;
import com.smallaswater.npc.data.RsNpcDefaultSkin;
import com.smallaswater.npc.dialog.DialogManager;
import com.smallaswater.npc.entitys.EntityRsNPC;
import com.smallaswater.npc.tasks.CheckNpcEntityTask;
import com.smallaswater.npc.utils.GameCoreDownload;
import com.smallaswater.npc.utils.MetricsLite;
import com.smallaswater.npc.utils.Utils;
import com.smallaswater.npc.utils.update.ConfigUpdateUtils;
import com.smallaswater.npc.variable.DefaultVariable;
import com.smallaswater.npc.variable.VariableManage;
import lombok.Getter;
import updata.AutoData;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class RsNPC extends PluginBase {

    public static final ThreadPoolExecutor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().availableProcessors() * 2,
            5,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Runtime.getRuntime().availableProcessors() * 4),
            new ThreadPoolExecutor.DiscardPolicy());
    public static final Random RANDOM = new Random();

    public static final String VERSION = "2.4.4-PNX";

    private static RsNPC rsNPC;

    @Getter
    private String setLang = "chs";
    @Getter
    private Language language;

    @Getter
    private final HashMap<String, Skin> skins = new HashMap<>();
    @Getter
    private final HashMap<String, RsNpcConfig> npcs = new HashMap<>();

    @Getter
    private DialogManager dialogManager;

    /**
     * Npc配置文件描述
     */
    private Config npcConfigDescription;

    public static RsNPC getInstance() {
        return rsNPC;
    }

    @Override
    public void onLoad() {
        rsNPC = this;

        VariableManage.addVariableV2("default", DefaultVariable.class);

        File skinFile = new File(getDataFolder() + "/Skins");
        if (!skinFile.exists() && !skinFile.mkdirs()) {
            this.getLogger().error("Skins文件夹创建失败");
        }
        File npcFile = new File(getDataFolder() + "/Npcs");
        if (!npcFile.exists() && !npcFile.mkdirs()) {
            this.getLogger().error("Npcs文件夹创建失败");
        }

        this.saveResource("Dialog/demo.yml", false);
    }

    @Override
    public void onEnable() {
        switch (GameCoreDownload.checkAndDownload()) {
            case 1:
                Server.getInstance().getPluginManager().disablePlugin(this);
                return;
            case 2:
                this.getServer().getScheduler().scheduleTask(this, () ->
                        this.getLogger().warning(this.getLanguage().translateString("plugin.depend.gamecore.needReload"))
                );
                break;
        }

        this.loadLanguage();

        try {
            if (Server.getInstance().getPluginManager().getPlugin("AutoUpData") != null) {
                if (AutoData.defaultUpDataByMaven(this, this.getFile(), "com.smallaswater", "RsNPC", "PNX")) {
                    return;
                }
            }
        } catch (Throwable e) {
            this.getLogger().warning(this.getLanguage().translateString("plugin.depend.autoupdata.error"));
        }

        this.getLogger().info(this.getLanguage().translateString("plugin.load.startLoad"));

        //检查插件分支是否和核心匹配
        NukkitTypeUtils.NukkitType nukkitType = NukkitTypeUtils.getNukkitType();
        if (nukkitType != NukkitTypeUtils.NukkitType.POWER_NUKKIT_X) {
            this.getLogger().error("警告！您所使用的插件版本不支持此Nukkit分支！");
            this.getLogger().error("服务器核心 : " + nukkitType.getShowName() + "  |  插件版本 : " + this.getVersion());
            this.getLogger().error("请使用PowerNukkitX核心！或更换为对应版本的插件！");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ConfigUpdateUtils.updateConfig();

        Entity.registerEntity("EntityRsNpc", EntityRsNPC.class);

        this.getLogger().info(this.getLanguage().translateString("plugin.load.startLoadDialog"));
        this.dialogManager = new DialogManager(this);

        this.getLogger().info(this.getLanguage().translateString("plugin.load.startLoadSkin"));
        this.loadPrivateSkins();
        this.loadSkins();

        this.getLogger().info(this.getLanguage().translateString("plugin.load.NPC.startLoad"));
        this.loadNpcs();

        this.getServer().getPluginManager().registerEvents(new OnListener(this), this);
        
        this.getServer().getScheduler().scheduleRepeatingTask(this, new CheckNpcEntityTask(this), 60);

        this.getServer().getCommandMap().register("RsNPC", new RsNPCCommand("RsNPC"));

        try {
            new MetricsLite(this, 16713);
        }catch (Exception ignored) {

        }

        this.getLogger().info(this.getLanguage().translateString("plugin.load.complete"));
    }

    @Override
    public void onDisable() {
        for (RsNpcConfig config : this.npcs.values()) {
            if (config.getEntityRsNpc() != null) {
                config.getEntityRsNpc().close();
            }
        }
        this.npcs.clear();
        this.getLogger().info(this.getLanguage().translateString("plugin.disable.complete"));
    }

    /**
     * 加载语言文件
     */
    private void loadLanguage() {
        this.setLang = this.getServer().getLanguage().getLang();
        Config config = new Config();
        InputStream resource = this.getResource("Language/" + this.setLang + "/Language.yml");
        if (resource == null) {
            this.getLogger().error("Language file not found: " + this.setLang + ".yml");
            this.setLang = "chs";
            resource = this.getResource("Language/chs/Language.yml");
        }
        config.load(resource);
        this.language = new Language(config);
        this.getLogger().info("Language: " + this.setLang + " loaded !");
    }

    private void loadNpcs() {
        File[] files = (new File(getDataFolder() + "/Npcs")).listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() && file.getName().endsWith(".yml")) {
                    continue;
                }
                String npcName = file.getName().split("\\.")[0];
                Config config;
                try {
                    config = new Config(file, Config.YAML);
                }catch (Exception e) {
                    this.getLogger().error(this.getLanguage().translateString("plugin.load.NPC.loadConfigError", npcName), e);
                    continue;
                }
                RsNpcConfig rsNpcConfig;
                try {
                    rsNpcConfig = new RsNpcConfig(npcName, config);
                } catch (Exception e) {
                    this.getLogger().error(this.getLanguage().translateString("plugin.load.NPC.loadError", npcName), e);
                    continue;
                }
                this.npcs.put(npcName, rsNpcConfig);
                this.getLogger().info(this.getLanguage().translateString("plugin.load.NPC.loadComplete", rsNpcConfig.getName()));
            }
        }
        this.getServer().getScheduler().scheduleDelayedTask(this, () -> {
            for (RsNpcConfig config : this.npcs.values()) {
                config.checkEntity();
            }
        }, 1);
    }

    /**
     * 加载内置skin
     */
    private void loadPrivateSkins() {
        this.skins.put("private_steve", RsNpcDefaultSkin.getSkin());
        String[] skins = { "male", "sugarfi_slim", "yuming_slim" };
        for (String skinName : skins) {
            try {
                ImageInputStream imageInputStream = ImageIO.createImageInputStream(this.getResource("Skins/" + skinName + ".png"));
                Skin skin = new Skin();
                skin.setSkinData(ImageIO.read(imageInputStream));
                SerializedImage.fromLegacy(skin.getSkinData().data); //检查非空和图片大小

                if (skinName.contains("_slim")) {
                    skin.setSkinResourcePatch(Skin.GEOMETRY_CUSTOM_SLIM);
                }
                skin.setTrusted(true);

                this.skins.put("private_" + skinName, skin);
            } catch (Exception e) {
                this.getLogger().error("Plugin built-in skin loading failed!", e);
            }
        }
    }

    private void loadSkins() {
        File[] files = new File(this.getDataFolder() + "/Skins").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String skinName = file.getName();

            File skinDataFile = null;
            boolean isSlim = true;
            if (file.isFile() && skinName.endsWith(".png")) {
                skinName = skinName.replace(".png", "");
                skinDataFile = file;
                if (skinName.contains("_slim")) {
                    skinName = skinName.replace("_slim", "");
                } else {
                    isSlim = false;
                }
            }else if (file.isDirectory()) {
                skinDataFile = new File(this.getDataFolder() + "/Skins/" + skinName + "/skin_slim.png");
                if (!skinDataFile.exists()) {
                    skinDataFile = new File(this.getDataFolder() + "/Skins/" + skinName + "/skin.png");
                    isSlim = false;
                }
            }

            if (skinDataFile != null && skinDataFile.exists()) {
                Skin skin = new Skin();

                skin.setSkinId(skinName);

                try {
                    skin.setSkinData(ImageIO.read(skinDataFile));
                    SerializedImage.fromLegacy(skin.getSkinData().data); //检查非空和图片大小

                    if (isSlim) {
                        skin.setSkinResourcePatch(Skin.GEOMETRY_CUSTOM_SLIM);
                    }
                } catch (Exception e) {
                    this.getLogger().error(this.getLanguage().translateString("plugin.load.skin.dataError", skinName), e);
                    continue;
                }

                //如果是4Dskin
                try {
                    File skinJsonFile = null;
                    if (file.isFile()) {
                        skinJsonFile = new File(this.getDataFolder() + "/Skins/" + skinName + ".json");
                    }else if (file.isDirectory()) {
                        skinJsonFile = new File(this.getDataFolder() + "/Skins/" + skinName + "/skin.json");
                    }
                    if (skinJsonFile != null && skinJsonFile.exists()) {
                        Map<String, Object> skinJson = (new Config(this.getDataFolder() + "/Skins/" + skinName + "/skin.json", Config.JSON)).getAll();
                        String geometryName = null;

                        String formatVersion = (String) skinJson.getOrDefault("format_version", "1.10.0");
                        skin.setGeometryDataEngineVersion(formatVersion); //设置skin版本，主流格式有1.16.0,1.12.0(Blockbench新模型),1.10.0(Blockbench Legacy模型),1.8.0
                        switch (formatVersion) {
                            case "1.16.0":
                            case "1.12.0":
                                geometryName = getGeometryName(skinJsonFile);
                                if (geometryName.equals("nullvalue")) {
                                    this.getLogger().error(this.getLanguage().translateString("plugin.load.skin.jsonDataIncompatible", skinName));
                                } else {
                                    skin.generateSkinId(skinName);
                                    skin.setSkinResourcePatch("{\"geometry\":{\"default\":\"" + geometryName + "\"}}");
                                    skin.setGeometryName(geometryName);
                                    skin.setGeometryData(Utils.readFile(skinJsonFile));
                                }
                                break;
                            default:
                                this.getLogger().warning("[" + skinJsonFile.getName() + "] 的版本格式为：" + formatVersion + "，正在尝试加载！");
                            case "1.10.0":
                            case "1.8.0":
                                for (Map.Entry<String, Object> entry : skinJson.entrySet()) {
                                    if (geometryName == null) {
                                        if (entry.getKey().startsWith("geometry")) {
                                            geometryName = entry.getKey();
                                        }
                                    } else {
                                        break;
                                    }
                                }
                                skin.generateSkinId(skinName);
                                skin.setSkinResourcePatch("{\"geometry\":{\"default\":\"" + geometryName + "\"}}");
                                skin.setGeometryName(geometryName);
                                skin.setGeometryData(Utils.readFile(skinJsonFile));
                                break;
                        }
                    }
                }catch (Exception e) {
                    this.getLogger().error(this.getLanguage().translateString("plugin.load.skin.jsonDataError", skinName), e);
                }

                skin.setTrusted(true);

                if (skin.isValid()) {
                    this.skins.put(skinName, skin);
                    this.getLogger().info(this.getLanguage().translateString("plugin.load.skin.loadSucceed", skinName));
                } else {
                    this.getLogger().error(this.getLanguage().translateString("plugin.load.skin.loadFailure", skinName));
                }
            } else {
                this.getLogger().error(this.getLanguage().translateString("plugin.load.skin.nameError", skinName));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public String getGeometryName(File file) {
        Config originGeometry = new Config(file, Config.JSON);
        if (!originGeometry.getString("format_version").equals("1.12.0") && !originGeometry.getString("format_version").equals("1.16.0")) {
            return "nullvalue";
        }
        //先读取minecraft:geometry下面的项目
        List<Map<String, Object>> geometryList = (List<Map<String, Object>>) originGeometry.get("minecraft:geometry");
        //不知道为何这里改成了数组，所以按照示例文件读取第一项
        Map<String, Object> geometryMain = geometryList.get(0);
        //获取description内的所有
        Map<String, Object> descriptions = (Map<String, Object>) geometryMain.get("description");
        return (String) descriptions.getOrDefault("identifier", "geometry.unknown"); //获取identifier
    }

    public void reload() {
        this.npcs.clear();
        for (Level level : Server.getInstance().getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity instanceof EntityRsNPC) {
                    entity.close();
                }
            }
        }
        if (this.dialogManager != null) {
            this.dialogManager.loadAllDialog();
        }
        this.loadSkins();
        this.loadNpcs();
    }

    public Skin getSkinByName(String name) {
        Skin skin = this.getSkins().get(name);
        if (skin == null) {
            skin = RsNpcDefaultSkin.getSkin();
        }
        return skin;
    }

    public Config getNpcConfigDescription() {
        if (this.npcConfigDescription == null) {
            this.npcConfigDescription = new Config();
            InputStream resource = this.getResource("Language/" + this.setLang + "/NpcConfigDescription.yml");
            if (resource == null) {
                resource = this.getResource("Language/chs/NpcConfigDescription.yml");
            }
            this.npcConfigDescription.load(resource);
        }
        return this.npcConfigDescription;
    }

    public String getVersion() {
        return VERSION;
        /*Config config = new Config(Config.PROPERTIES);
        config.load(this.getResource("git.properties"));
        return config.get("git.build.version", this.getDescription().getVersion()) + " git-" + config.get("git.commit.id.abbrev", "Unknown");*/
    }

}
