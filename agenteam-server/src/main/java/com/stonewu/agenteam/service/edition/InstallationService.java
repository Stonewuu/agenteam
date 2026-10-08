package com.stonewu.agenteam.service.edition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.edition.InstallationMapper;
import com.stonewu.agenteam.mapper.auth.SystemSuperAdminLockMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.model.edition.entity.InstallationRow;
import com.stonewu.agenteam.model.edition.entity.ProductEdition;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** 初始化事务始终先取得固定部署行锁；记录丢失时拒绝重建新的部署或企业。 */
@Service
public class InstallationService {
    private final InstallationMapper installations;
    private final EnterpriseTableMapper enterprises;
    private final EditionDescriptor edition;
    private final SystemSuperAdminLockMapper administrators;

    public InstallationService(InstallationMapper installations, EnterpriseTableMapper enterprises,
                               EditionDescriptor edition, SystemSuperAdminLockMapper administrators) {
        this.installations = installations;
        this.enterprises = enterprises;
        this.edition = edition;
        this.administrators = administrators;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockBeforeBootstrap() {
        requireUninitialized(installations.lockInstallation());
        if (enterprises.selectCount(new LambdaQueryWrapper<EnterpriseRow>()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INITIALIZATION_STATE_INVALID",
                "当前数据库已有企业但初始化记录不完整，请先恢复备份或完成商业版迁移。");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireBootstrapAdministrator(String userId) {
        var administrator = administrators.selectById(1);
        if (administrator == null || !administrator.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INITIALIZATION_ACTOR_INVALID",
                "初始企业必须由首次初始化的管理员创建。");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInitialEnterprise(String enterpriseId, Instant now) {
        var state = requireUninitialized(installations.lockInstallation());
        if (enterprises.selectCount(new LambdaQueryWrapper<EnterpriseRow>()) != 1
            || enterprises.selectById(enterpriseId) == null) {
            throw new IllegalStateException("首次初始化必须在同一事务中创建且只创建一个企业");
        }
        int changed = installations.update(new LambdaUpdateWrapper<InstallationRow>()
            .eq(InstallationRow::getId, 1).eq(InstallationRow::getRevision, state.getRevision())
            .isNull(InstallationRow::getInstallationId)
            .set(InstallationRow::getInstallationId, UUID.randomUUID().toString())
            .set(InstallationRow::getInitialEnterpriseId, enterpriseId)
            .set(InstallationRow::getEdition, edition.edition().name().toLowerCase(Locale.ROOT))
            .set(InstallationRow::getInitializedAt, now).set(InstallationRow::getUpdatedAt, now)
            .set(InstallationRow::getRevision, state.getRevision() + 1));
        if (changed != 1) {
            throw new IllegalStateException("部署记录已经变化，首次初始化未提交");
        }
    }

    public InstallationRow current() {
        return requireRecord(installations.selectById(1));
    }

    private InstallationRow requireUninitialized(InstallationRow state) {
        requireRecord(state);
        if (state.getInstallationId() != null || state.getInitialEnterpriseId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_ALREADY_INITIALIZED", "系统已经初始化，请前往登录。");
        }
        return state;
    }

    private InstallationRow requireRecord(InstallationRow state) {
        if (state == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INSTALLATION_RECORD_MISSING",
                "部署记录不完整，请联系管理员恢复备份。");
        }
        if (edition.edition() == ProductEdition.COMMUNITY && !"community".equals(state.getEdition())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INSTALLATION_EDITION_MISMATCH",
                "数据库与当前安装版本不一致，请使用商业版程序启动。");
        }
        return state;
    }
}
