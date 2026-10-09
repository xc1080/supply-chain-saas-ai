package com.ruoyi.framework.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.framework.datasource.TenantRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 用户验证处理
 *
 * @author KrityCat
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysPasswordService passwordService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired private ObjectProvider<TenantRegistry> tenantRegistry;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String previous=TenantContext.get();
        try {
        // Resolve the global account directory first, then authenticate the account
        // maintained by its own tenant. Headers cannot choose the credential store.
        TenantContext.clear();
        SysUser user = userService.selectUserByUserName(username);
        TenantRegistry registry=tenantRegistry.getIfAvailable();
        if(user!=null&&registry!=null) {
            Long directoryId=user.getUserId();
            TenantContext.set(registry.tenantFor(directoryId));
            user=userService.selectUserById(directoryId);
            if(user!=null&&!username.equals(user.getUserName()))user=null;
        }
        if (StringUtils.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        } else if (UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        } else if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", username);
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }

        passwordService.validate(user);

        return createLoginUser(user);
        } finally { TenantContext.set(previous); }
    }

    public UserDetails createLoginUser(SysUser user) {
        if(user.getRoles()!=null)user.getRoles().removeIf(role->!"0".equals(role.getStatus()));
        LoginUser result=new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
        result.setTenantId(TenantContext.get());
        return result;
    }
}
