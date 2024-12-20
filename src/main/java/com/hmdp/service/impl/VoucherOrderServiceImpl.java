package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        // 3、判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已经结束
            return Result.fail("秒杀已经结束!");
        }
        // 4、判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足!");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象(新增代码)
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁对象
        boolean isLock = lock.tryLock(1200);
        //加锁失败
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 一人一单 - 添加悲观锁
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5、一人一单
        Long userId = UserHolder.getUser().getId();
        // 方法内部加锁，id值一样的作为一把锁
        // intern() 这个方法是从常量池中拿到数据

        // 5.1、查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2、判断是否存在
        if (count > 0) {
            return Result.fail("用户已经购买过一次了！");
        }

        // 6、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        // where id = ? and stock > 0;

        if (!success) {
            // 扣减失败
            return Result.fail("库存不足!");
        }
        // 7、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1、订单id，用全局Id生成器
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2、用户id
        //Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 7.3、代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 8、返回订单id
        return Result.ok(orderId);

    }
}
