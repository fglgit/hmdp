package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPS_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key=CACHE_SHOPS_KEY;
        String value=stringRedisTemplate.opsForValue().get(key);
        if(value!=null){
            List<ShopType> list= JSONUtil.toList(value,ShopType.class);
            return Result.ok(list);
        }
        List<ShopType> list=list();
        if(list==null||list.size()==0){
            return Result.fail("未查询到店铺的种类");
        }
        value=JSONUtil.toJsonStr(list);
        stringRedisTemplate.opsForValue().set(key,value);

        return Result.ok(list);
    }
}
