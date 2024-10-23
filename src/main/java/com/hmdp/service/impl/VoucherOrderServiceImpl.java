package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdIncrement;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 感觉最好是是在service层次引入mapper这样层次分工明确
    private final SeckillVoucherMapper seckillVoucherMapper;

    private final VoucherOrderMapper voucherOrderMapper;

    private final RedisIdIncrement redisIdIncrement;

    /**
     * 抢购优惠券(秒杀下单)
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);

        // 对于异常情况的处理
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束");
        }
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(seckillVoucher);
        }

    }

    /**
     * 虽然加在方法上是可行的，但是粒度太大了，这样的话每一个用户都要排队了
     * 但是我的本意是一个用户一单，也就是一个用户的多次访问要排队，所以要锁唯一标识用户的
     * 这个锁的范围是要大于事务的范围，要在事务提交之后才释放锁，要不然又会有多个线程在锁没提交就进入
     * @param seckillVoucher
     * @return
     */
    @Transactional
    public Result createVoucherOrder(SeckillVoucher seckillVoucher) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, seckillVoucher.getVoucherId());
        long count = voucherOrderMapper.selectCount(queryWrapper);

        if (count > 0) {
            return Result.fail("用户已经购买过了一次");
        }

        // 解决超卖的问题（乐观锁， 悲观锁）
        // 1 悲观锁（其实是数据库在更新操作时，是会对修改的这几行做一个排他锁）
        // 这里库存的减少和数据库的更新最好是原子操作，这样是能够避免脏读和幻读
        // 扣减库存
        int result = seckillVoucherMapper.updateWithDecreaseStock(seckillVoucher);
        if (result == 0) {
            return Result.fail("库存不足");
        }

        // 2 乐观锁（其实是不符合条件的认为是其他线程在修改，符合条件的一定是唯一在修改的）
//        int result = seckillVoucherMapper.updateWithDecreaseStockOptimistic(seckillVoucher);
//        if (result == 0) {
//            return Result.fail("库存不足");
//        }

        // 生成订单(需要orderId, userId, voucherId)
        Long orderId = redisIdIncrement.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(seckillVoucher.getVoucherId())
                .build();
        voucherOrderMapper.insert(voucherOrder);
        return Result.ok(orderId);

    }


}
