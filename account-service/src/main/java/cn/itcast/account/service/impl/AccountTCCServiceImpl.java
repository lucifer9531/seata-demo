package cn.itcast.account.service.impl;

import cn.itcast.account.entity.AccountFreeze;
import cn.itcast.account.mapper.AccountFreezeMapper;
import cn.itcast.account.mapper.AccountMapper;
import cn.itcast.account.service.AccountTCCService;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.api.BusinessActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountTCCServiceImpl implements AccountTCCService {

    private final AccountMapper accountMapper;
    private final AccountFreezeMapper accountFreezeMapper;

    @Override
    public void deduct(String userId, int money) {
        // 0.获取事务ID
        String xid = RootContext.getXID();
        // 判断freeze中是否有冻结记录 如果有 一定是CANCEL执行过 我要拒绝业务
        AccountFreeze oldFreeze = accountFreezeMapper.selectById(xid);
        if (oldFreeze != null) {
            // Cancel 执行过 我要拒绝业务
            return;
        }
        // 1.扣减可用余额
        accountMapper.deduct(userId, money);
        // 2.记录冻结金额， 事务状态
        AccountFreeze freeze = new AccountFreeze();
        freeze.setUserId(userId);
        freeze.setFreezeMoney(money);
        freeze.setState(AccountFreeze.State.TRY);
        freeze.setXid(xid);
        accountFreezeMapper.insert(freeze);
    }

    @Override
    public boolean confirm(BusinessActionContext ctx) {
        // 1.获取事务ID
        String xid = ctx.getXid();
        int count = accountFreezeMapper.deleteById(xid);
        // 2.根据ID删除冻结记录
        return count == 1;
    }

    @Override
    public boolean cancel(BusinessActionContext ctx) {
        // 0.查询冻结记录
        String xid = ctx.getXid();
        String userId = ctx.getActionContext("userId").toString();
        AccountFreeze freeze = accountFreezeMapper.selectById(xid);
        // 1.空回滚判断 判断freeze 是否是null 为null 证明try没执行 需要空回滚
        if (freeze == null) {
            // 证明try没执行 需要空回滚
            freeze = new AccountFreeze();
            freeze.setUserId(userId);
            freeze.setFreezeMoney(0);
            freeze.setState(AccountFreeze.State.CANCEL);
            freeze.setXid(xid);
            return true;
        }
        // 2.判断幂等
        if (freeze.getState() == AccountFreeze.State.CANCEL) {
            // 已经处理过cancel 无需重复处理
            return true;
        }
        // 1.恢复可用余额
        accountMapper.refund(freeze.getUserId(), freeze.getFreezeMoney());
        // 2.将冻结金额清零，状态改为cancel
        freeze.setFreezeMoney(0);
        freeze.setState(AccountFreeze.State.CANCEL);
        int count = accountFreezeMapper.updateById(freeze);
        return count == 1;
    }
}
