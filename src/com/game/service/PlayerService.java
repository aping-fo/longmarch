package com.game.service;

import com.game.SysConfig;
import com.game.dao.GroupDAO;
import com.game.dao.LoginLogDAO;
import com.game.dao.PlayerDAO;
import com.game.data.LevelCfg;
import com.game.domain.group.Group;
import com.game.domain.group.GroupMember;
import com.game.domain.group.GroupRecord;
import com.game.domain.player.Player;
import com.game.sdk.http.HttpClient;
import com.game.sdk.net.Result;
import com.game.sdk.proto.*;
import com.game.sdk.proto.vo.*;
import com.game.sdk.utils.DecoderHandler;
import com.game.sdk.utils.ErrorCode;
import com.game.util.ConfigData;
import com.game.util.JsonUtils;
import com.game.util.TimeUtil;
import com.game.util.TimerService;
import com.google.common.cache.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class PlayerService extends AbstractService {
    private static Logger logger = Logger.getLogger(PlayerService.class);
    public final String ROBOT_ID = "ROBOT_UUID_888";
    public static final int signStep = 80;//签到每天奖励
    private final static int MAX_SIZE = 8;
    private List<GroupRankVO> groupRankVOs = Lists.newArrayList();
    private Map<String, String> sessionMap = Maps.newHashMap();         //session保存，一段时间清空

    private Map<String, Map<String, Integer>> giveStepMap = Maps.newHashMap();  //赠送步数映射

    @Autowired
    private TimerService timerService;
    @Autowired
    private PlayerDAO playerDAO;
    @Autowired
    private LoginLogDAO loginLogDAO;
    @Autowired
    private GroupDAO groupDAO;

    private final int TransferStepType_Today = 1;
    private final int TransferStepType_Game = 2;
    private final int TransferStepType_Sign = 3;
    private final int TransferStepType_Give = 4;

    //TODO 可以优化为停服时统一保存
    private final LoadingCache<String, Player> players = CacheBuilder.newBuilder()
            .expireAfterAccess(600, TimeUnit.SECONDS)
            .maximumSize(5000)
            .removalListener(new RemovalListener<String, Player>() {

                @Override
                public void onRemoval(RemovalNotification<String, Player> notification) {
                    playerDAO.saveOrUpdate(notification.getValue());
                }
            })
            .build(new CacheLoader<String, Player>() {
                @Override
                public Player load(String openId) throws Exception {
                    logger.info("Cache loaded for " + openId);
                    Player player = playerDAO.queryPlayer(openId);

                    return player;
                }
            });

    /**
     * 家庭
     */
    private final LoadingCache<String, Group> groups = CacheBuilder.newBuilder()
            .expireAfterAccess(600, TimeUnit.SECONDS)
            .maximumSize(5000)
            .removalListener(new RemovalListener<String, Group>() {

                @Override
                public void onRemoval(RemovalNotification<String, Group> notification) {
                    groupDAO.saveOrUpdate(notification.getValue());
                }
            })
            .build(new CacheLoader<String, Group>() {
                @Override
                public Group load(String id) throws Exception {
                    logger.info("Cache loaded for " + id);
                    Group group = groupDAO.queryGroup(id);
                    if (group != null) {

                        String json = group.getMemberJson();
                        if (json != null && json.length() > 0) {
                            Map<String, GroupMember> members = JsonUtils.string2Map(json, String.class, GroupMember.class);
                            group.getMembers().putAll(members);
                            group.updateAddPercent();
                        }

                        json = group.getRecordJson();
                        if (json != null && json.length() > 0) {
                            List<GroupRecord> records = JsonUtils.string2Collection(json, List.class, GroupRecord.class);
                            group.getRecords().addAll(records);
                        }
                    }
                    return group;
                }
            });


    @Override
    public void onStart() {
        //5分钟定时
        timerService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    schedule();
                } catch (Exception e) {
                    logger.error("match schedule error", e);
                }
            }
        }, 0, 5, TimeUnit.MINUTES);
    }

    /**
     * 获取玩家信息
     *
     * @param openId
     * @return
     */
    public Player getPlayer(String openId) {
        try {
            return players.get(openId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取玩家信息
     *
     * @param openId
     * @return
     */
    public Group getGroup(String openId) {
        try {
            return groups.get(openId);
        } catch (Exception e) {
            //ignore
            return null;
        }
    }

    /**
     * 创建角色
     *
     * @param openId
     * @param nickName
     */
    public Result createPlayer(String openId, String nickName, String iconUrl) {
        Player player = new Player();
        player.setNickName(nickName);
        player.setOpenId(openId);
        player.setLevel(1);
        player.setStep(0);
        player.setTotalStep(0);
        player.setIconUrl(iconUrl);
        player.setCreateTime(System.currentTimeMillis());
        player.setLoginTime(System.currentTimeMillis());
        playerDAO.insert(player);
        players.put(openId, player);

        this.createGroup(openId, nickName);

        return Result.valueOf(ErrorCode.OK, "ok");
    }

    /**
     * 获取OPENID
     *
     * @param openId
     * @param code
     * @return
     * @throws Exception
     */
    public Result getOpenID(String openId, String code) throws Exception {
        String errorCode = ErrorCode.OK;
        OpenIDResp resp = new OpenIDResp();
        String session = "";
        if (!"".equals(code))//测试模式
        {
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + SysConfig.wxAppid + "&secret=" + SysConfig.wxAppSecret + "&grant_type=authorization_code&js_code=" + code;
            String json = HttpClient.sendGetRequest(url);
            Map<String, Object> result = JsonUtils.string2Map(json);
            openId = (String) result.get("openid");
            session = (String) result.get("session_key");
        } else {
            session = "test" + openId;
        }
        try {
            players.get(openId);
            resp.setHasRole(true);
        } catch (Exception e) {
            resp.setHasRole(false);
        }

        logger.warn("player openId: " + openId + ", session: " + session);

        sessionMap.put(openId, session);
        resp.setOpenId(openId);

        return Result.valueOf(errorCode, resp);
    }

    /**
     * 创建角色
     *
     * @param openId
     */
    public Result getRole(String openId) {
        String code = ErrorCode.OK;
        Map<String, Object> resp = Maps.newHashMap();
        Player player = players.getUnchecked(openId);
        if (player == null) {
            code = ErrorCode.ROLE_NOT_EXIST;
        } else {
            resp.put("openId", player.getOpenId());
            resp.put("level", player.getLevel());
            resp.put("step", player.getStep());
            resp.put("totalStep", player.getTotalStep());
            resp.put("nickName", player.getNickName());

            LevelCfg cfg = ConfigData.getConfig(LevelCfg.class, player.getLevel());
            if (cfg != null) {
                resp.put("levelUpExp", cfg.levelUpScore);
            } else {
                resp.put("levelUpExp", 0);
            }

            Instant instant1 = Instant.ofEpochMilli(player.getLoginTime());
            LocalDateTime localDateTime1 = LocalDateTime.ofInstant(instant1, ZoneId.systemDefault());
            Instant instant2 = Instant.ofEpochMilli(System.currentTimeMillis());
            LocalDateTime localDateTime2 = LocalDateTime.ofInstant(instant2, ZoneId.systemDefault());
            if (!localDateTime1.toLocalDate().isEqual(localDateTime2.toLocalDate())) { //每日重置
                loginLogDAO.insert(player.getOpenId(), new Date());
                player.setTodayTransStep(0);
            }
            player.setLoginTime(System.currentTimeMillis());
            playerDAO.saveOrUpdate(player);
        }

        return Result.valueOf(code, resp);
    }

    /**
     * 每局胜利奖励
     *
     * @param openId
     * @param deltaStep
     * @return
     */
    public void roundResult(String openId, int deltaStep) {
        Player player = players.getUnchecked(openId);
        player.setLastGameStepChange(deltaStep);

        this.transStep(openId, deltaStep, TransferStepType_Game);
    }

    private void schedule() {
        logger.warn("rank .....");

        List<Group> families = groupDAO.queryGroupRank();
        groupRankVOs.clear();
        for (Group group : families) {
            GroupRankVO vo = group.toRankProto();
            groupRankVOs.add(vo);
        }
    }

    /**
     * 更新角色信息
     *
     * @param openId
     */
    public Result updateRole(String openId, String nick, String iconUrl) {
        Player player = players.getUnchecked(openId);
        if (nick != null && nick.length() > 0) {
            player.setNickName(nick);
        }
        if (iconUrl != null && iconUrl.length() > 0) {
            player.setIconUrl(iconUrl);
        }
        return Result.valueOf(ErrorCode.OK, "ok");
    }

    public Result getGroupInfo(String openId) {
        GroupInfoResp resp = new GroupInfoResp();
        Player player = players.getUnchecked(openId);
        if (player.getGroupId() == null || "".equals(player.getGroupId())) {
            return Result.valueOf(ErrorCode.OK, resp);
        }

        Group group = groups.getUnchecked(player.getGroupId());
        resp.setInfo(group.toProto());
        resp.setRank(getGroupRank(group));
        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result getGroupRecord(String openId) {
        Player player = players.getUnchecked(openId);
        if (player.getGroupId() == null || "".equals(player.getGroupId())) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "erro");
        }

        Group group = groups.getUnchecked(player.getGroupId());
        List<GroupRecordVO> resp = group.toRecordsProto();
        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result groupMemberAdd(String openId, String id) {
        GroupInfoResp resp = new GroupInfoResp();
        Player player = players.getUnchecked(openId);
        Group oldGroup = getGroup(player.getGroupId());

        if (oldGroup != null && oldGroup.getMembers().size() >= 2) {
            //表示已经有群 不能创建
            return Result.valueOf(ErrorCode.GROUP_EXIST, "error");
        }

        Group group = getGroup(id);
        if (group == null) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        if (group.getMembers().size() > MAX_SIZE) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_MAX_SIZE, "error");
        }

        if (oldGroup != null) {
            dismissGroup(openId, false);
        }

        GroupMember member = createMember(player);
        group.add(member, player.getStep());

        player.setGroupId(id);

        updatePlayerStepInGroup(group);
        groupDAO.saveOrUpdate(group);

        resp.setInfo(group.toProto());
        resp.setRank(getGroupRank(group));

        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result groupMemberRemove(String openId, String memberOpenId) {
        GroupInfoResp resp = new GroupInfoResp();
        Player player = players.getUnchecked(openId);

        if (StringUtils.isEmpty(player.getGroupId())) {
            //没有群，不能移除成员
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        if (!group.getOwnerId().equals(openId)) {
            //不是群主
            return Result.valueOf(ErrorCode.GROUP_NOT_CREATOR, "error");
        }
        group.remove(memberOpenId);
        Player memberPlayer = players.getUnchecked(memberOpenId);
        memberPlayer.setGroupId("");

        updatePlayerStepInGroup(group);
        groupDAO.saveOrUpdate(group);

        resp.setInfo(group.toProto());
        resp.setRank(getGroupRank(group));

        return Result.valueOf(ErrorCode.OK, resp);
    }

    /**
     * 成员退出
     *
     * @param openId
     * @return
     */
    public Result groupMemberExit(String openId) {
        Player player = players.getUnchecked(openId);

        if (StringUtils.isEmpty(player.getGroupId())) {
            //没有群，不能退出
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        group.remove(openId);
        updatePlayerStepInGroup(group);
        groupDAO.saveOrUpdate(group);

        player.setGroupId("");
        this.createGroup(openId, player.getNickName());

        return Result.valueOf(ErrorCode.OK, "");
    }

    public Result dismissGroup(String openId, boolean isAutoCreateGroup) {
        Player player = players.getUnchecked(openId);
        Group group = getGroup(player.getGroupId());

        if (group == null) {
            //没有群，不能解散
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        if (!openId.equals(group.getOwnerId())) {
            //不是群主
            return Result.valueOf(ErrorCode.GROUP_NOT_CREATOR, "error");
        }

        if (group.getMembers().size() <= 1) {
            //只有1人 不需要解散
            return Result.valueOf(ErrorCode.GROUP_MEMBER_ONLY_ONE, "error");
        }

        int totalStepPerMember = group.getTotalStep();

        for (String id : group.getMembers().keySet()) {
            Player p = getPlayer(id);
            p.setGroupId("");
            p.setTotalStep(totalStepPerMember);

            if (isAutoCreateGroup) {
                this.createGroup(p.getOpenId(), p.getNickName());
            } else {
                playerDAO.saveOrUpdate(p);
            }
        }

        groups.invalidate(group.getId());
        groupDAO.deleteGroup(group.getId());
        for (int index = 0; index < groupRankVOs.size(); index++) {
            if (groupRankVOs.get(index).getId().equals(group.getId())) {
                groupRankVOs.remove(index);
                break;
            }
        }
        return Result.valueOf(ErrorCode.OK, "");
    }

    public Result encourageGroupMemeber(String openId, String otherNickName) {
        Player player = players.getUnchecked(openId);
        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //没有群，不能鼓励
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        group.addRecord(Group.RECORD_TYPE_ENCOURAGE, player.getNickName(), otherNickName);

        return Result.valueOf(ErrorCode.OK, "OK");
    }

    public Result warnGroupMemeber(String openId, String otherNickName) {
        Player player = players.getUnchecked(openId);
        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //没有群，不能警告
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        group.addRecord(Group.RECORD_TYPE_WARN, player.getNickName(), otherNickName);

        return Result.valueOf(ErrorCode.OK, "OK");
    }

    public Result groupChangeName(String openId, String name) {
        GroupInfoResp resp = new GroupInfoResp();
        Player player = players.getUnchecked(openId);

        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        group.setName(name);
        resp.setInfo(group.toProto());
        resp.setRank(getGroupRank(group));
        groupDAO.saveOrUpdate(group);
        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result groupChangeIcon(String openId, String iconUrl) {
        GroupInfoResp resp = new GroupInfoResp();
        Player player = players.getUnchecked(openId);

        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //加群不存在
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        group.setIconUrl(iconUrl);
        resp.setInfo(group.toProto());
        resp.setRank(getGroupRank(group));
        groupDAO.saveOrUpdate(group);
        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result getGroupRank(String openid) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("result", groupRankVOs);
        return Result.valueOf(ErrorCode.OK, resp);
    }

    public Result sign(String openId) {
        Player player = getPlayer(openId);
        long signTime = player.getSignTime();
        long today = TimeUtil.getTodayBeginTime();
        if (TimeUtil.isSameDate(today, signTime)) {
            return Result.valueOf(ErrorCode.REPEAT_CHECK_IN, "error");
        }

        player.setSignTime(today);
        this.transStep(openId, signStep, TransferStepType_Sign);

        return Result.valueOf(ErrorCode.OK, signStep);
    }

    /**
     * 创建组
     *
     * @param openId
     * @param groupName
     * @return
     */
    private boolean createGroup(String openId, String groupName) {
        Player player = players.getUnchecked(openId);

        if (player.getGroupId() != null && !"".equals(player.getGroupId())) {
            //表示已经有组 不能创建
            return false;
        }

        //查询重复组id
        String groupId = UUID.randomUUID().toString();
        while (groupDAO.queryGroup(groupId) != null) {
            groupId = UUID.randomUUID().toString();
        }

        player.setGroupId(groupId);
        Group group = new Group();
        group.setId(groupId);
        group.setOwnerId(openId);
        group.setIconUrl(player.getIconUrl());
        group.setName(groupName);

        GroupMember member = createMember(player);
        group.add(member, player.getStep());
        groups.put(group.getId(), group);

        playerDAO.saveOrUpdate(player);
        groupDAO.saveOrUpdate(group);

        return true;
    }

    /**
     * 创建组员
     *
     * @param player
     * @return
     */
    private GroupMember createMember(Player player) {
        GroupMember member = new GroupMember();
        member.setIconUrl(player.getIconUrl());
        member.setStep(0);
        member.setName(player.getNickName());
        member.setOpenid(player.getOpenId());

        return member;
    }

    /**
     * 向微信端获取步数
     *
     * @param openId
     * @param encryptedData
     * @param iv
     * @return
     */
    public Result getRunData(String openId, String encryptedData, String iv) {
        String sessionKey = sessionMap.getOrDefault(openId, "");
        if (sessionKey.equals("")) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        int step = 0;
        //测试
        if (sessionKey.startsWith("test")) {
            step = p.getTotalStep() + 10000;
        } else {
            String json = DecoderHandler.decrypt(encryptedData, iv, sessionKey);
            if (json == null) {
                return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
            }

            Map<String, Object> result = JsonUtils.string2Map(json);
            Object[] steps = ((ArrayList) result.get("stepInfoList")).toArray();
            Map<String, Integer> lastStep = (Map<String, Integer>) steps[steps.length - 1];
            int timeStamp = lastStep.get("timestamp");
            boolean isSameDay = TimeUtil.isSameDate(Calendar.getInstance().getTimeInMillis() / 1000, timeStamp);

            step = isSameDay ? lastStep.get("step") : 0;
        }
        int todayTransStep = p.getTodayTransStep();
        int add = step - todayTransStep;

        return Result.valueOf(ErrorCode.OK, add);
    }

    public Result transStep(String openId, int transferStep, int type) {
        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }
        int currentStep = p.getStep();
        int totalStep = p.getTotalStep();

        //查看组的加成
        Group group = getGroup(p.getGroupId());

        if (type == TransferStepType_Today) {
            //今天的步伐
            int todayTransStep = p.getTodayTransStep();
            p.setTodayTransStep(todayTransStep + transferStep);
        }
        p.setStep(currentStep + transferStep);

        if (group != null) {
            group.addStep(p.getOpenId(), transferStep);
            group.addRecord(Group.RECORD_TYPE_ADDSTEP, p.getNickName(), String.valueOf(transferStep));
            p.setTotalStep(group.getTotalStep());

            groupDAO.saveOrUpdate(group);
        } else {
            p.setTotalStep(totalStep + transferStep);
        }

        playerDAO.saveOrUpdate(p);

        return Result.valueOf(ErrorCode.OK, transferStep);
    }

    public Result giveStep(String openId, String giveOpenId, int giveStep) {
        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        Player givePlayer = getPlayer(giveOpenId);
        if (givePlayer == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        //今天减去步数
        int todayTransStep = p.getTodayTransStep();
        p.setTodayTransStep(todayTransStep + giveStep);
        playerDAO.saveOrUpdate(p);

        Map<String, Integer> map = giveStepMap.getOrDefault(giveOpenId, null);
        if (map == null) {
            map = Maps.newHashMap();
            giveStepMap.put(giveOpenId, map);
        }
        int newGiveStep = map.getOrDefault(openId, 0) + giveStep;
        map.put(openId, newGiveStep);

        return Result.valueOf(ErrorCode.OK, giveStep);
    }

    public Result getGiveStep(String openId) {
        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        List<GiveStepVO> ret = Lists.newArrayList();
        Map<String, Integer> map = giveStepMap.getOrDefault(openId, null);

        if (map != null) {
            for (String giveOpenId : map.keySet()) {
                Player player = getPlayer(giveOpenId);
                if (player != null) {
                    GiveStepVO vo = new GiveStepVO();

                    vo.setOpenId(giveOpenId);
                    vo.setNickName(player.getNickName());
                    vo.setStep(map.get(giveOpenId));

                    ret.add(vo);
                }
            }
        }

        return Result.valueOf(ErrorCode.OK, ret);
    }

    public Result transferGiveRunData(String openId, String giveOpenId) {
        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        Map<String, Integer> map = giveStepMap.get(openId);
        if (map == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        int transferStep = map.getOrDefault(giveOpenId, 0);
        map.put(giveOpenId, 0);

        this.transStep(openId, transferStep, TransferStepType_Give);

        return Result.valueOf(ErrorCode.OK, 0);
    }

    public Result getGroupsInfoNearBy(String openId) {
        Player p = getPlayer(openId);
        if (p == null) {
            return Result.valueOf(ErrorCode.PARAM_ERROR, "error");
        }

        List<Group> list = groupDAO.queryGroup();
        List<GroupSimpleVO> ret = Lists.newArrayListWithCapacity(list.size());

        for (Group group : list) {
            GroupSimpleVO vo = group.toSimpleProto();
            ret.add(vo);
        }

        return Result.valueOf(ErrorCode.OK, ret);
    }


    /**
     * 设置团步数后同步团成员步数
     *
     * @param group
     */
    private void updatePlayerStepInGroup(Group group) {
        for (GroupMember member : group.getMembers().values()) {
            Player player = players.getUnchecked(member.getOpenid());
            player.setTotalStep(group.getTotalStep());

            playerDAO.saveOrUpdate(player);
        }
    }

    private int getGroupRank(Group group) {
        int rank = 1;
        for (GroupRankVO groupVo : groupRankVOs) {
            if (groupVo.getId().equals(group.getId())) {
                return rank;
            }
            rank++;
        }

        return 0;
    }

    public Result updateGroupHeadicon(String openId, String headicon) {
        Player player = players.getUnchecked(openId);
        Group group = getGroup(player.getGroupId());
        if (group == null) {
            //没有群，不能鼓励
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }
        String path = System.getProperty("user.dir") + File.separator + "icon" + File.separator;
        File f = new File(path + headicon);
        if (!f.exists()) {
            return Result.valueOf(ErrorCode.GROUP_NOT_EXIST, "error");
        }

        f = new File(path + group.getIconUrl());
        f.delete();
        group.setIconUrl(headicon);
        return Result.valueOf(ErrorCode.OK, "error");
    }
}
