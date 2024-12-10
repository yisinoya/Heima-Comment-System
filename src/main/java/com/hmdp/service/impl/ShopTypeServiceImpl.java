package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collections;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1、从redis中查询店铺类别缓存
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key,0,-1);
        // 2、判断是否命中缓存
        if(!CollectionUtils.isEmpty(shopTypeJson)){
            // 3、存在，直接返回
            // 使用stream流将json集合转为bean集合
            List<ShopType> shopTypeList = shopTypeJson.stream()
                    .map(item-> JSONUtil.toBean(item,ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        // 4、不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if(CollectionUtils.isEmpty(shopTypeList)){
            // 不存在则缓存一个空集合，解决缓存穿透
            // 申明一个空列表
            String str = Collections.emptyList().toString();
            stringRedisTemplate.opsForValue().set(key,str,CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.fail("商品分类信息为空");
        }
        // 5、数据存在，先写入redis，再返回
        // 使用stream流将bean集合转为json集合
        List<String> shopTypeCache = shopTypeList.stream()
                .sorted(Comparator.comparingInt(ShopType::getSort))
                .map(item->JSONUtil.toJsonStr(item))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypeCache);
        stringRedisTemplate.expire(key,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return Result.ok(shopTypeList);
    }
}
