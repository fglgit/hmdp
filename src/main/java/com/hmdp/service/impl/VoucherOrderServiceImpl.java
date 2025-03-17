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
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result setkillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }

        if(voucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        //一人一单
        Long userId =UserHolder.getUser().getId();
        int count=query().eq("user_id",userId).eq("voucher_id",voucherId).count();
        if(count>0){
            //已经购买过了
            return Result.fail("用户已经购买过一次！");
        }
        //原始直接修改
        //boolean success=seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).update();

        //乐观锁，只有当前数据与先前查到的一样才减一
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1") //set stock = stock -1
//                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update();

        //乐观锁，大于0就可以
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1") //set stock = stock -1
                .eq("voucher_id", voucherId).gt("stock",0).update();

        if(!success){
            return Result.fail("库存不足！");
        };

        VoucherOrder voucherOrder=new VoucherOrder();
        long orderId=redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        userId= UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
