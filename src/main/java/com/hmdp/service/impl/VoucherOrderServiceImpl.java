package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.thread.ThreadUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
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
    //线程池
    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取redis消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list=stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断是否成功
                    if(list==null||list.isEmpty()){
                        //获取失败，则说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析订单信息
                    MapRecord<String, Object, Object> mapRecord=list.get(0);
                    Map<Object,Object> value=mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    hanndlePendingList();
                }
            }
        }

        private void hanndlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list=stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断是否成功
                    if(list==null||list.isEmpty()){
                        //获取失败，则说明pending-list没有消息结束
                        break;
                    }
                    //解析订单信息
                    MapRecord<String, Object, Object> mapRecord=list.get(0);
                    Map<Object,Object> value=mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.如果获取成功，则可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    ThreadUtil.sleep(500);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private  IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId=UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),String.valueOf(orderId)
        );
        int r=result.intValue();
        // 2.判断结果是否为0
        if(r!=0){
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return  Result.ok(orderId);
    }
    //这里是异步执行了，所以不能通过UserHolder里面得到userid
    @Override
    @Transactional
    public  void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return ;
        }
        //保存订单信息到数据库中
        save(voucherOrder);
    }
}
