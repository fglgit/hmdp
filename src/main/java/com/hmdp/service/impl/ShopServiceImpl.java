package com.hmdp.service.impl;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        String key= CACHE_SHOP_KEY+id.toString();
//        //1、从redis中查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2、判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //3、存在
//            Shop shop= JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        if(shopJson!=null){
//            //查到了空值说明不存在店铺信息
//            return Result.fail("店铺不存在");
//        }
//        //4、不存在，直接mybatis-plus查找
//        Shop shop =getById(id);
//        //5、查找不到，则报错设置空值
//        if(shop == null){
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
//        }
//        //6、往redis里面存储
//        shopJson=JSONUtil.toJsonStr(shop);
//        stringRedisTemplate.opsForValue().set(key,shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存穿透
        //Shop shop=queryWithPassThrough(id);

        //缓存击穿
        //Shop shop=queryWithMutex(id);
        Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    public Shop queryWithPassThrough(Long id) {
        String key= CACHE_SHOP_KEY+id.toString();
        //1、从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){
            //查到了空值说明不存在店铺信息
            return null;
        }
        //4、不存在，直接mybatis-plus查找
        Shop shop =getById(id);
        //5、查找不到，则报错设置空值
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6、往redis里面存储
        shopJson=JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String key= CACHE_SHOP_KEY+id.toString();
        //1、从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){
            //查到了空值说明不存在店铺信息
            return null;
        }

        //获取互斥锁，每个店铺有一个对应的锁
        String lockKey=LOCK_SHOP_KEY+id.toString();
        Shop shop=null;
        try {

            //循环等待
            if (!tryLock(lockKey)) {
                ThreadUtil.sleep(50);
                return queryWithMutex(id);
            }

            //4、不存在，直接mybatis-plus查找
            shop = getById(id);
            //模拟查询的延迟
            ThreadUtil.sleep(200);
            //5、查找不到，则报错设置空值
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6、往redis里面存储
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, shopJson, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {

            //释放锁
            unlock(lockKey);
        }
        return shop;
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop queryWithLogicalExpire(Long id) {
        //查询redis中的shop缓存
        String key= CACHE_SHOP_KEY+id.toString();
        String shopJson =stringRedisTemplate.opsForValue().get(key);
        //如果没有查询到，则返回空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //查询到了
        RedisData redisData=JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject)(redisData.getData()), Shop.class);
        LocalDateTime expireTime =redisData.getExpireTime();
        //判断是否逻辑过期
        System.out.println(expireTime+"     "+LocalDateTime.now());
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期则直接返回
            return shop;
        }
        //过期了，则获取锁，然后执行更新的方法
        if(tryLock(LOCK_SHOP_KEY+id.toString())){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(LOCK_SHOP_KEY+id.toString());
                }
            });
        }
        return shop;
    }

    //新线程用于更新逻辑过期的redis缓存
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {

        Shop shop=getById(id);
        //模拟延迟
        Thread.sleep(2000);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id.toString(),JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        System.out.println("删除缓存前");
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id.toString());
        System.out.println("删除缓存后");
        return Result.ok();
    }

    private boolean tryLock(String key) {
        //获取一个锁，setIfAbsent即redis里面的setnx操作
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        //释放锁
        stringRedisTemplate.delete(key);
    }
}
