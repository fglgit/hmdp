package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
        String key= CACHE_SHOP_KEY+id.toString();
        //1、从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在
            Shop shop= JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //4、不存在，直接mybatis-plus查找
        Shop shop =getById(id);
        //5、查找不到，则报错
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        //6、往redis里面存储
        shopJson=JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
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
}
