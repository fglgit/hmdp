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

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        stringRedisTemplate.opsForValue().set(key,shopJson);

        return Result.ok(shop);
    }
}
