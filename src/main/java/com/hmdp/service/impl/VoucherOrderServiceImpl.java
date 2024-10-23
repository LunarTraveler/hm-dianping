package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdIncrement;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
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
    @Transactional
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

        // 解决超卖的问题（乐观锁， 悲观锁）
        // 1 悲观锁（其实是数据库在更新操作时，是会对修改的这几行做一个排他锁）
        // 这里库存的减少和数据库的更新最好是原子操作，这样是能够避免脏读和幻读
        // 扣减库存
//        int result = seckillVoucherMapper.updateWithDecreaseStock(seckillVoucher);
//        if (result == 0) {
//            return Result.fail("库存不足");
//        }

        // 2 乐观锁（其实是不符合条件的认为是其他线程在修改，符合条件的一定是唯一在修改的）
        int result = seckillVoucherMapper.updateWithDecreaseStockOptimistic(seckillVoucher);
        if (result == 0) {
            return Result.fail("库存不足");
        }

        // 生成订单(需要orderId, userId, voucherId)
        Long orderId = redisIdIncrement.nextId("order");
        Long userId = UserHolder.getUser().getId();
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .build();
        voucherOrderMapper.insert(voucherOrder);
        // 返回订单id给前端展示
        return Result.ok(orderId);

    }

}
