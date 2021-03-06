package com.sdg.cmdb.service.impl;


import com.sdg.cmdb.dao.cmdb.KeyboxDao;
import com.sdg.cmdb.dao.cmdb.ServerDao;
import com.sdg.cmdb.dao.cmdb.ServerGroupDao;
import com.sdg.cmdb.dao.cmdb.UserDao;
import com.sdg.cmdb.dao.jumpserver.JumpserverDao;
import com.sdg.cmdb.domain.BusinessWrapper;
import com.sdg.cmdb.domain.auth.UserDO;
import com.sdg.cmdb.domain.jumpserver.*;
import com.sdg.cmdb.domain.server.ServerDO;
import com.sdg.cmdb.domain.server.ServerGroupDO;
import com.sdg.cmdb.service.CacheKeyService;
import com.sdg.cmdb.service.ConfigServerGroupService;
import com.sdg.cmdb.service.ConfigService;
import com.sdg.cmdb.service.JumpserverService;
import com.sdg.cmdb.service.jms.model.User;
import com.sdg.cmdb.service.jms.service.JmsUsersService;
import com.sdg.cmdb.util.TimeUtils;
import com.sdg.cmdb.util.UUIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class JumpserverServiceImpl implements JumpserverService {

    private static final Logger logger = LoggerFactory.getLogger(JumpserverServiceImpl.class);


    @Value(value = "${jumpserver.host}")
    private String jumpserverHost;

    @Value(value = "${coco.host}")
    private String cocoHost;

    @Value(value = "${jumpserver.user}")
    private String jumpserverUser;

    @Value(value = "${jumpserver.passwd}")
    private String jumpserverPasswd;

    // TODO 用户组前缀
    public final String USERGROUP_PREFIX = "usergroup_";
    public final String SERVERGROUP_PREFIX = "group_";
    // TODO 授权规则前缀
    public final String PERMS_PREFIX = "perms_";

    // TODO 过期时间
    public final String DATE_EXPIRED = "2089-01-01 00:00:00";

    public final String CACHE_KEY = "JumpserverServiceImpl:";

    // 管理员用户组 绑定 根节点
    public final String USERGROUP_ADMINISTRATORS = "usergroup_administrators";
    // 管理员授权
    public final String PERMS_ADMINISTRATORS = "perms_administrators";

    @Autowired
    private CacheKeyService cacheKeyService;

    @Autowired
    private JumpserverDao jumpserverDao;

    @Autowired
    private ServerGroupDao serverGroupDao;

    @Autowired
    private ServerDao serverDao;

    @Autowired
    private KeyboxDao keyboxDao;

    @Autowired
    private UserDao userDao;


    @Autowired
    private ConfigServerGroupService configServerGroupService;

    /**
     * TODO 获取随机主键
     *
     * @return
     */
    private String getId() {
        return UUIDUtils.getUUIDByShort();
    }

    private String getNowDate() {
        return TimeUtils.gmtNowDate();
    }

    private String getAdminuserId() {
        return cacheKeyService.getKeyByString(CACHE_KEY + "AdminuserId");
    }

    /**
     * 创建资产（主机）
     * 需要解决一个问题 公网链接服务器删除内网服务器
     *
     * @param serverDO
     * @return
     */
    private AssetsAssetDO createAssetsAsset(ServerDO serverDO, String comment) {
        if (StringUtils.isEmpty(comment))
            comment = "";
        // Kubernetes服务器增加标签
        String label = getServerLabel(serverDO);
        if (!StringUtils.isEmpty(label))
            comment = label;

        String adminuserId = getAdminuserId();
        if (StringUtils.isEmpty(adminuserId))
            return null;
        AssetsAssetDO assetsAssetDO = getAssetsAsset(serverDO);
        // 更新服务器信息
        if (assetsAssetDO != null) {
            if (!StringUtils.isEmpty(comment)) {
                assetsAssetDO.setComment(comment);
            } else {
                assetsAssetDO.setComment(serverDO.getContent());
            }
            if (!StringUtils.isEmpty(serverDO.getPublicIp()))
                assetsAssetDO.setComment(assetsAssetDO.getComment() + "(公网:" + serverDO.getPublicIp() + ")");
            assetsAssetDO.setHostname(serverDO.acqServerName());
            //assetsAssetDO.setCreated_by("oc auto");
            jumpserverDao.updateAssetsAsset(assetsAssetDO);
        } else {
            assetsAssetDO = new AssetsAssetDO(getId(), configServerGroupService.queryGetwayIp(serverDO), serverDO, adminuserId);
            if (!StringUtils.isEmpty(comment))
                assetsAssetDO.setComment(comment);
            jumpserverDao.addAssetsAsset(assetsAssetDO);
        }
        //  System.err.println(assetsAssetDO);
        return assetsAssetDO;
    }

    /**
     * 查询资产是否录入
     *
     * @param serverDO
     * @return
     */
    private AssetsAssetDO getAssetsAsset(ServerDO serverDO) {
        String ip = configServerGroupService.queryGetwayIp(serverDO);
        AssetsAssetDO assetsAssetDO = jumpserverDao.getAssetsAssetByIp(ip);
        if (assetsAssetDO == null) {
            assetsAssetDO = jumpserverDao.getAssetsAssetByHostname(serverDO.acqServerName());
            if (assetsAssetDO != null) {
                // TODO 更新IP 此服务器可能是使用公网连接
                assetsAssetDO.setIp(ip);
                jumpserverDao.updateAssetsAsset(assetsAssetDO);
                return assetsAssetDO;
            }
        }
        return assetsAssetDO;
    }

    /**
     * 2019.4.17 修改计算节点最大值算法
     * 创建节点（服务器组）
     *
     * @param serverGroupDO
     * @return
     */
    private AssetsNodeDO createAssetsNode(ServerGroupDO serverGroupDO) {
        try {
            AssetsNodeDO assetsNodeDO = jumpserverDao.getAssetsNodeByValue(serverGroupDO.getName());
            // 节点已录入
            if (assetsNodeDO != null) return assetsNodeDO;
            assetsNodeDO = new AssetsNodeDO(getId(), serverGroupDO, getAssetsNodeKey(), getNowDate());
            jumpserverDao.addAssetsNode(assetsNodeDO);
            return assetsNodeDO;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 取节点Key(按最后数据逐条查询)
     *
     * @return
     */
    public String getAssetsNodeKey() {
        AssetsNodeDO lastNode = jumpserverDao.getAssetsNodeLastOne();
        String[] keys = lastNode.getKey().split(":");
        if (keys.length == 1)
            return "1:1";
        int k = Integer.valueOf(keys[1]);
        String keyName;
        while (true) {
            k++;
            keyName = "1:" + k;
            AssetsNodeDO checkAN = jumpserverDao.getAssetsNodeByKey(keyName);
            if (checkAN == null) return keyName;
        }
    }

    /**
     * 绑定资产到节点
     *
     * @param assetsAssetDO
     * @param assetsNodeDO
     * @return
     */
    private AssetsAssetNodesDO bindAssetsAssetNodes(AssetsAssetDO assetsAssetDO, AssetsNodeDO assetsNodeDO) {
        if (assetsAssetDO == null || assetsNodeDO == null) return null;
        AssetsAssetNodesDO assetsAssetNodesDO = new AssetsAssetNodesDO(assetsAssetDO.getId(), assetsNodeDO.getId());
        AssetsAssetNodesDO checkDO = jumpserverDao.getAssetsAssetNodes(assetsAssetNodesDO);
        if (checkDO == null) {
            try {
                jumpserverDao.addAssetsAssetNodes(assetsAssetNodesDO);
                return assetsAssetNodesDO;
            } catch (Exception e) {
                logger.error("Jumpserver 授权资产到节点 Error !");
                return null;
            }
        }
        return checkDO;
    }

    /**
     * 创建用户组
     *
     * @param serverGroupDO
     * @return
     */
    private UsersUsergroupDO createUsersUsergroup(ServerGroupDO serverGroupDO) {
        String name = serverGroupDO.getName().replace(SERVERGROUP_PREFIX, USERGROUP_PREFIX);
        return createUsersUsergroup(name, serverGroupDO.getContent());
    }

    private UsersUsergroupDO createUsersUsergroup(String name, String content) {
        UsersUsergroupDO usersUsergroupDO = jumpserverDao.getUsersUsergroupByName(name);
        if (usersUsergroupDO != null) return usersUsergroupDO;
        usersUsergroupDO = new UsersUsergroupDO(getId(), name, content, getNowDate());
        try {
            jumpserverDao.addUsersUsergroup(usersUsergroupDO);
            return usersUsergroupDO;
        } catch (Exception e) {
            logger.error("Jumpserver 创建用户组 {} Error !", name);
            return null;
        }
    }

    /**
     * 创建授权策略
     *
     * @param serverGroupDO
     * @param assetsNodeDO
     * @param usersUsergroupDO
     * @return
     */
    private PermsAssetpermissionDO createPermsAssetpermission(ServerGroupDO serverGroupDO, AssetsNodeDO assetsNodeDO, UsersUsergroupDO usersUsergroupDO) {
        String name = serverGroupDO.getName().replace(SERVERGROUP_PREFIX, PERMS_PREFIX);

        PermsAssetpermissionDO permsAssetpermissionDO = jumpserverDao.getPermsAssetpermissionByName(name);
        if (permsAssetpermissionDO == null) {
            // TODO 新增授权策略
            permsAssetpermissionDO = new PermsAssetpermissionDO(getId(), name, DATE_EXPIRED, getNowDate());
            try {
                jumpserverDao.addPermsAssetpermission(permsAssetpermissionDO);
            } catch (Exception e) {
                return null;
            }
        }
        // TODO 绑定系统账户
        bindPermsAssetpermissionSystemUsers(permsAssetpermissionDO);
        // TODO 绑定用户组
        PermsAssetpermissionUserGroupsDO permsAssetpermissionUserGroupsDO = bindPermsAssetpermissionUserGroups(permsAssetpermissionDO, usersUsergroupDO);
        // TODO 绑定节点
        PermsAssetpermissionNodesDO bindPermsAssetpermissionNodes = bindPermsAssetpermissionNodes(permsAssetpermissionDO, assetsNodeDO);
        return permsAssetpermissionDO;
    }

    /**
     * 授权绑定用户组
     *
     * @param permsAssetpermissionDO
     * @param usersUsergroupDO
     * @return
     */
    private PermsAssetpermissionUserGroupsDO bindPermsAssetpermissionUserGroups(PermsAssetpermissionDO permsAssetpermissionDO, UsersUsergroupDO usersUsergroupDO) {
        PermsAssetpermissionUserGroupsDO permsAssetpermissionUserGroupsDO = new PermsAssetpermissionUserGroupsDO(permsAssetpermissionDO.getId(), usersUsergroupDO.getId());
        PermsAssetpermissionUserGroupsDO checkDO = jumpserverDao.getPermsAssetpermissionUserGroups(permsAssetpermissionUserGroupsDO);
        if (checkDO == null) {
            try {
                jumpserverDao.addPermsAssetpermissionUserGroups(permsAssetpermissionUserGroupsDO);
                return permsAssetpermissionUserGroupsDO;
            } catch (Exception e) {
                logger.error("Jumpserver 授权绑定用户组 Error !");
                return null;
            }
        }
        return checkDO;
    }

    /**
     * 授权绑定节点
     *
     * @param permsAssetpermissionDO
     * @param assetsNodeDO
     * @return
     */
    private PermsAssetpermissionNodesDO bindPermsAssetpermissionNodes(PermsAssetpermissionDO permsAssetpermissionDO, AssetsNodeDO assetsNodeDO) {
        PermsAssetpermissionNodesDO permsAssetpermissionNodesDO = new PermsAssetpermissionNodesDO(permsAssetpermissionDO.getId(), assetsNodeDO.getId());
        PermsAssetpermissionNodesDO checkDO = jumpserverDao.getPermsAssetpermissionNodes(permsAssetpermissionNodesDO);
        if (checkDO == null) {
            try {
                jumpserverDao.addPermsAssetpermissionNodes(permsAssetpermissionNodesDO);
                return permsAssetpermissionNodesDO;
            } catch (Exception e) {
                logger.error("Jumpserver 授权绑定节点 Error !");
                return null;
            }
        }
        return checkDO;
    }

    private String getSystemuserId() {
        return cacheKeyService.getKeyByString(CACHE_KEY + "SystemuserId");
    }

    /**
     * @param permsAssetpermissionDO
     * @return
     */
    private PermsAssetpermissionSystemUsersDO bindPermsAssetpermissionSystemUsers(PermsAssetpermissionDO permsAssetpermissionDO) {
        String systemuserId = getSystemuserId();
        if (StringUtils.isEmpty(systemuserId))
            return null;
        PermsAssetpermissionSystemUsersDO permsAssetpermissionSystemUsersDO = new PermsAssetpermissionSystemUsersDO(permsAssetpermissionDO.getId(), systemuserId);
        PermsAssetpermissionSystemUsersDO checkDO = jumpserverDao.getPermsAssetpermissionSystemUsers(permsAssetpermissionSystemUsersDO);
        if (checkDO == null) {
            try {
                jumpserverDao.addPermsAssetpermissionSystemUsers(permsAssetpermissionSystemUsersDO);
                return permsAssetpermissionSystemUsersDO;
            } catch (Exception e) {
                logger.error("Jumpserver 授权绑定系统账户 Error !");
                return null;
            }
        }
        return checkDO;


    }

    /**
     * 同步资产 包括（主机，服务器组，用户组，授权等）
     *
     * @return
     */
    @Override
    public BusinessWrapper<Boolean> syncAssets() {
        List<ServerGroupDO> groupList = serverGroupDao.queryServerGroup();
        for (ServerGroupDO serverGroupDO : groupList) {
            // 创建资产节点（服务器组）
            AssetsNodeDO assetsNodeDO = createAssetsNode(serverGroupDO);
            // 创建用户组
            UsersUsergroupDO usersUsergroupDO = createUsersUsergroup(serverGroupDO);
            if (assetsNodeDO == null) {
                logger.error("Jumpserver 同步节点（服务器组）{} Error !", serverGroupDO.getName());
                continue;
            }
            // 创建授权并绑定 节点，用户组，系统账户
            PermsAssetpermissionDO permsAssetpermissionDO = createPermsAssetpermission(serverGroupDO, assetsNodeDO, usersUsergroupDO);
            // 同步资产并绑定 节点
            List<ServerDO> serverList = serverDao.acqServersByGroupId(serverGroupDO.getId());
            for (ServerDO serverDO : serverList) {
                try {
                    // 创建资产（主机）
                    AssetsAssetDO assetsAssetDO = createAssetsAsset(serverDO, serverGroupDO.getContent());
                    // 绑定资产到节点
                    bindAssetsAssetNodes(assetsAssetDO, assetsNodeDO);
                    // 资产绑定系统账户
                    bindAvssetsSystemuserAssets(assetsAssetDO.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return new BusinessWrapper<Boolean>(true);
    }


    /**
     * 新增资产
     *
     * @param serverDO
     */
    @Override
    public void addAssets(ServerDO serverDO) {

        try {
            ServerGroupDO serverGroupDO = serverGroupDao.queryServerGroupById(serverDO.getServerGroupId());
            // TODO 创建资产节点（服务器组）
            AssetsNodeDO assetsNodeDO = createAssetsNode(serverGroupDO);
            // TODO 创建用户组
            UsersUsergroupDO usersUsergroupDO = createUsersUsergroup(serverGroupDO);
            if (assetsNodeDO == null) {
                logger.error("Jumpserver 同步节点（服务器组）{} Error !", serverGroupDO.getName());
                return;
            }
            // TODO 创建授权并绑定 节点，用户组，系统账户
            PermsAssetpermissionDO permsAssetpermissionDO = createPermsAssetpermission(serverGroupDO, assetsNodeDO, usersUsergroupDO);
            // TODO 创建资产（主机）
            AssetsAssetDO assetsAssetDO = createAssetsAsset(serverDO, "");
            // TODO 绑定资产到节点
            bindAssetsAssetNodes(assetsAssetDO, assetsNodeDO);
            // TODO 资产绑定系统账户
            bindAvssetsSystemuserAssets(assetsAssetDO.getId());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 资产绑定系统账户
     */
    private void bindAvssetsSystemuserAssets(String assetId) {
        String systemuser_id = getSystemuserId();
        AvssetsSystemuserAssetsDO avssetsSystemuserAssetsDO = new AvssetsSystemuserAssetsDO(systemuser_id, assetId);
        if (jumpserverDao.getAvssetsSystemuserAssets(avssetsSystemuserAssetsDO) == null)
            jumpserverDao.addAvssetsSystemuserAssets(avssetsSystemuserAssetsDO);
    }


    /**
     * 同步用户（包括授权信息）
     */
    @Override
    public BusinessWrapper<Boolean> syncUsers() {
        List<UserDO> userList = userDao.getAllUser();
        for (UserDO userDO : userList) {
            bindUserGroups(userDO);
        }
        return new BusinessWrapper<Boolean>(true);
    }

    /**
     * 创建Jumpserver用户
     *
     * @param userDO
     * @return
     */
    private UsersUserDO createUsersUser(UserDO userDO) {
        UsersUserDO usersUserDO = jumpserverDao.getUsersUserByUsername(userDO.getUsername());
        if (usersUserDO == null) {
            //  public UsersUserDO(UserDO userDO, String id, String date_joined, String date_expired)
            usersUserDO = new UsersUserDO(userDO, getId(), getNowDate(), DATE_EXPIRED);
            try {
                jumpserverDao.addUsersUser(usersUserDO);
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("Jumpserver 创建用户{} Error !", userDO.getUsername());
                return null;
            }
        }
        return usersUserDO;
    }

    /**
     * 绑定用户-用户组
     *
     * @param userDO
     * @return
     */
    private void bindUserGroups(UserDO userDO) {
        // TODO 查询用户的所有授权的服务组
        List<ServerGroupDO> serverGroupList = keyboxDao.getGroupListByUsername(userDO.getUsername());
        // TODO 创建用户
        UsersUserDO usersUserDO = createUsersUser(userDO);
        for (ServerGroupDO serverGroupDO : serverGroupList)
            bindUserGroups(usersUserDO, serverGroupDO);
    }

    private void bindUserGroups(UsersUserDO usersUserDO, ServerGroupDO serverGroupDO) {
        // TODO 用户组名称
        String name = serverGroupDO.getName().replace(SERVERGROUP_PREFIX, USERGROUP_PREFIX);
        UsersUsergroupDO usersUsergroupDO = jumpserverDao.getUsersUsergroupByName(name);
        if (usersUsergroupDO == null)
            usersUsergroupDO = createUsersUsergroup(serverGroupDO);
        bindUserGroups(usersUserDO, usersUsergroupDO);
    }

    @Override
    public void bindUserGroup(UserDO userDO, ServerGroupDO serverGroupDO) {
        // TODO 创建用户
        UsersUserDO usersUserDO = createUsersUser(userDO);
        // TODO 用户绑定组
        bindUserGroups(usersUserDO, serverGroupDO);
    }

    @Override
    public void unbindUserGroup(UserDO userDO, ServerGroupDO serverGroupDO) {
        UsersUserDO usersUserDO = createUsersUser(userDO);
        if (usersUserDO == null) return;
        String name = serverGroupDO.getName().replace(SERVERGROUP_PREFIX, USERGROUP_PREFIX);
        UsersUsergroupDO usersUsergroupDO = jumpserverDao.getUsersUsergroupByName(name);
        UsersUserGroupsDO usersUserGroupsDO = new UsersUserGroupsDO(usersUserDO.getId(), usersUsergroupDO.getId());
        UsersUserGroupsDO checkDO = jumpserverDao.getUsersUserGroups(usersUserGroupsDO);
        jumpserverDao.delUsersUserGroups(checkDO.getId());
    }

    private boolean bindUserGroups(UsersUserDO usersUserDO, UsersUsergroupDO usersUsergroupDO) {
        if (usersUserDO == null || usersUsergroupDO == null) return false;
        UsersUserGroupsDO usersUserGroupsDO = new UsersUserGroupsDO(usersUserDO.getId(), usersUsergroupDO.getId());
        UsersUserGroupsDO checkDO = jumpserverDao.getUsersUserGroups(usersUserGroupsDO);
        if (checkDO == null) {
            try {
                jumpserverDao.addUsersUserGroups(usersUserGroupsDO);
            } catch (Exception e) {
                logger.error("Jumpserver 用户绑定用户组 Error !");
                return false;
            }
        }
        return true;
    }

    public List<UsersUserDO> getAdministrators() {
        // 查询超高级管理员用户组
        UsersUsergroupDO usersUsergroupDO = jumpserverDao.getUsersUsergroupByName(this.USERGROUP_ADMINISTRATORS);
        List<UsersUserDO> userList = new ArrayList<>();
        if (usersUsergroupDO == null)
            return userList;
        // 关联表
        List<UsersUserGroupsDO> usersUserGroupsList = jumpserverDao.queryUsersUserGroupsByUsergroupId(usersUsergroupDO.getId());
        for (UsersUserGroupsDO usersUserGroupsDO : usersUserGroupsList)
            userList.add(jumpserverDao.getUsersUser(usersUserGroupsDO.getUser_id()));
        return userList;
    }


    @Override
    public List<AssetsSystemuserDO> queryAssetsSystemuser(String name) {
        if (StringUtils.isEmpty(name))
            name = "";
        List<AssetsSystemuserDO> list = jumpserverDao.queryAssetsSystemuser(name);
        return list;
    }

    @Override
    public List<AssetsAdminuserDO> queryAssetsAdminuser(String name) {
        if (StringUtils.isEmpty(name))
            name = "";
        List<AssetsAdminuserDO> list = jumpserverDao.queryAssetsAdminuser(name);
        return list;
    }

    @Override
    public JumpserverVO getJumpserver() {
        JumpserverVO jumpserverVO = new JumpserverVO();

        if (StringUtils.isEmpty(jumpserverHost))
            return jumpserverVO;
        jumpserverVO.setHost(jumpserverHost);

        jumpserverVO.setCocoHost(cocoHost);
        String systemuserId = cacheKeyService.getKeyByString(this.CACHE_KEY + "SystemuserId");
        // TODO 插入配置的当前系统账户
        if (!StringUtils.isEmpty(systemuserId)) {
            AssetsSystemuserDO assetsSystemuserDO = jumpserverDao.getAssetsSystemuser(systemuserId);
            jumpserverVO.setAssetsSystemuserDO(assetsSystemuserDO);
        }

        String adminuserId = cacheKeyService.getKeyByString(this.CACHE_KEY + "AdminuserId");
        // TODO 插入配置的当前系统账户
        if (!StringUtils.isEmpty(adminuserId)) {
            AssetsAdminuserDO assetsAdminuserDO = jumpserverDao.getAssetsAdminuser(adminuserId);
            jumpserverVO.setAssetsAdminuserDO(assetsAdminuserDO);
        }
        // 用户统计
        jumpserverVO.setLocalUsersTotal(userDao.getUserTotal());
        jumpserverVO.setJumpserverUsersTotal(jumpserverDao.getUsersUserTotal());
        // 主机统计
        jumpserverVO.setLocalServersTotal(serverDao.getServerTotal());
        jumpserverVO.setJumpserverAssetsTotal(jumpserverDao.getAssetsAssetTotal());
        // 插入管理员列表
        jumpserverVO.setAdministrators(getAdministrators());
        // 插入终端列表
        jumpserverVO.setTerminals(jumpserverDao.queryTerminal());
        // 插入当前会话
        jumpserverVO.setTerminalSessions(jumpserverDao.queryTerminalSession());
        // 插入闲置用户和资产(user表中不存在的用户/server表中不存在的主机)
        jumpserverVO.setInactiveUsers(jumpserverDao.queryUsersUserByIsActive(false));
        jumpserverVO.setInactiveAssets(jumpserverDao.queryAssetsAssetByIsActive(false));
        return jumpserverVO;
    }

    /**
     * 校验用户（将oc中不存在的用户 is_active = false）
     *
     * @return
     */
    @Override
    public BusinessWrapper<Boolean> checkUsers() {
        List<UsersUserDO> userList = jumpserverDao.queryUsersUserByIsActive(true);
        for (UsersUserDO usersUserDO : userList) {
            UserDO userDO = userDao.getUserByName(usersUserDO.getUsername());
            // TODO 用户不存在则标记is_active = false
            if (userDO == null) {
                usersUserDO.set_active(false);
                try {
                    jumpserverDao.updateUsersUser(usersUserDO);
                } catch (Exception e) {
                    e.printStackTrace();
                    return new BusinessWrapper<Boolean>(false);
                }
            }
        }
        return new BusinessWrapper<Boolean>(true);
    }

    /**
     * 校验资产（将oc中不存在的服务器 is_active = false）
     *
     * @return
     */
    @Override
    public BusinessWrapper<Boolean> checkAssets() {
        List<AssetsAssetDO> AssetList = jumpserverDao.queryAssetsAssetByIsActive(true);
        for (AssetsAssetDO assetsAssetDO : AssetList) {
            ServerDO serverDO = serverDao.queryServerByInsideIp(assetsAssetDO.getIp());
            if (serverDO == null)
                serverDO = serverDao.queryServerByPublicIp(assetsAssetDO.getIp());

            // TODO 用户不存在则标记is_active = false
            if (serverDO == null) {
                assetsAssetDO.set_active(false);
                try {
                    jumpserverDao.updateAssetsAsset(assetsAssetDO);
                } catch (Exception e) {
                    e.printStackTrace();
                    return new BusinessWrapper<Boolean>(false);
                }
            }
        }
        return new BusinessWrapper<Boolean>(true);
    }

    @Override
    public BusinessWrapper<Boolean> delAssets(String id) {
        /**
         *
         assets_asset_nodes
         assets_systemuser_assets
         ops_adhoc_hosts
         */
        try {
            jumpserverDao.delAssetsAsset(id);
            return new BusinessWrapper<Boolean>(true);
        } catch (Exception e) {
            e.printStackTrace();
            return new BusinessWrapper<Boolean>(false);
        }
    }

    @Override
    public BusinessWrapper<Boolean> delUsers(String id) {
        try {
            jumpserverDao.delUsersUser(id);
            return new BusinessWrapper<Boolean>(true);
        } catch (Exception e) {
            return new BusinessWrapper<Boolean>(false);
        }
    }


    /**
     * 授权用户成为管理员（绑定根节点）
     *
     * @param userId
     * @return
     */
    @Override
    public BusinessWrapper<Boolean> authAdmin(long userId) {
        UserDO userDO = userDao.getUserById(userId);
        if (userDO == null) return new BusinessWrapper<Boolean>(false);
        UsersUserDO usersUserDO = createUsersUser(userDO);
        // TODO 用户组名称
        UsersUsergroupDO usersUsergroupDO = jumpserverDao.getUsersUsergroupByName(USERGROUP_ADMINISTRATORS);
        if (usersUsergroupDO == null)
            usersUsergroupDO = createUsersUsergroup(USERGROUP_ADMINISTRATORS, "Administrators");
        bindUserGroups(usersUserDO, usersUsergroupDO);
        // TODO 建立根节点绑定关系
        PermsAssetpermissionDO permsAssetpermissionDO = jumpserverDao.getPermsAssetpermissionByName(PERMS_ADMINISTRATORS);
        if (permsAssetpermissionDO == null) {
            // TODO 新增授权策略
            permsAssetpermissionDO = new PermsAssetpermissionDO(getId(), PERMS_ADMINISTRATORS, DATE_EXPIRED, getNowDate());
            try {
                jumpserverDao.addPermsAssetpermission(permsAssetpermissionDO);
            } catch (Exception e) {
                return new BusinessWrapper<Boolean>(false);
            }
        }
        // TODO 绑定系统账户
        bindPermsAssetpermissionSystemUsers(permsAssetpermissionDO);
        // TODO 绑定用户组
        PermsAssetpermissionUserGroupsDO permsAssetpermissionUserGroupsDO = bindPermsAssetpermissionUserGroups(permsAssetpermissionDO, usersUsergroupDO);
        // TODO 绑定根节点
        AssetsNodeDO assetsNodeDO = jumpserverDao.getAssetsNodeRoot();
        if (assetsNodeDO == null) {
            logger.error("Jumpserver 查询根节点失败 !");
            return new BusinessWrapper<Boolean>(false);
        }
        PermsAssetpermissionNodesDO bindPermsAssetpermissionNodes = bindPermsAssetpermissionNodes(permsAssetpermissionDO, assetsNodeDO);
        return new BusinessWrapper<Boolean>(true);
    }

    @Override
    public BusinessWrapper<Boolean> saveAssetsSystemuser(String id) {
        if (StringUtils.isEmpty(id))
            return new BusinessWrapper<Boolean>(true);
        try {
            cacheKeyService.set(CACHE_KEY + "SystemuserId", id);
            return new BusinessWrapper<Boolean>(true);
        } catch (Exception e) {
            return new BusinessWrapper<Boolean>(false);
        }
    }

    @Override
    public BusinessWrapper<Boolean> saveAssetsAdminuser(String id) {
        if (StringUtils.isEmpty(id))
            return new BusinessWrapper<Boolean>(true);
        try {
            cacheKeyService.set(CACHE_KEY + "AdminuserId", id);
            return new BusinessWrapper<Boolean>(true);
        } catch (Exception e) {
            return new BusinessWrapper<Boolean>(false);
        }
    }

    @Override
    public BusinessWrapper<Boolean> updateUserPubkey(String username) {
        UserDO userDO = userDao.getUserByName(username);
        // TODO 用户不存在或pubKey不存在
        if (userDO == null || StringUtils.isEmpty(userDO.getRsaKey()))
            return new BusinessWrapper<Boolean>(false);
        UsersUserDO usersUserDO = createUsersUser(userDO);
        if (usersUserDO == null)
            return new BusinessWrapper<Boolean>(false);
        try {
            JmsUsersService jmsUsersService = new JmsUsersService(jumpserverHost, jumpserverUser, jumpserverPasswd);
            User user = new User();
            String uuid = UUIDUtils.convertUUID(usersUserDO.getId());
            user.setId(uuid);
            user.setPublicKey(userDO.getRsaKey());

            Map<String, String> map = jmsUsersService.userPubkeyReset(user);
            return new BusinessWrapper<Boolean>(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new BusinessWrapper<Boolean>(false);

    }

    @Override
    public boolean isSetKey(String username) {
        UsersUserDO usersUserDO = jumpserverDao.getUsersUserByUsername(username);
        if (usersUserDO == null) return false;
        if (StringUtils.isEmpty(usersUserDO.get_public_key())) return false;
        return true;
    }

    private String getServerLabel(ServerDO serverDO) {
        String label = configServerGroupService.queryKubernetesLabel(serverDO);
        if (StringUtils.isEmpty(label)) {
            return "";
        } else {
            return "( " + label + ")";
        }
    }

}
