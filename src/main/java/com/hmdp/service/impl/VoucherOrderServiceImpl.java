package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5.Ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePandingList();
                }
            }
        }

        private void handlePandingList() {
            while (true) {
                try {
                    // 1.获取 panding-list 中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.如果获取失败，说明panding-list中没有异常消息，结束循环
                        break;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 5.Ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理panding-list异常", e);
                    handlePandingList();
                    // 怕太频繁了，休眠20毫秒
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }


    // 创建阻塞队列
    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
/*    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行 lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行 lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5、一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1、查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2、判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次了！");
            return;
        }
        // 6、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();
        if (!success) {
            // 扣减失败
            log.error("库存不足!");
            return;
        }
        save(voucherOrder);
    }


/*    @Override
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
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        //boolean isLock = lock.tryLock(1200);
        boolean isLock = lock.tryLock();
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
    }*/

/*  // 一人一单 - 添加悲观锁
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

    }*/
}
