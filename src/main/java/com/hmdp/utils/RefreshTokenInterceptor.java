package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1、从请求头获取token
        String token=request.getHeader("authorization");
        //2、判断token是否为空
        if(StrUtil.isBlank(token)){
            //空的也放行
            //但是不保存在ThreadLocal
            return true;
        }
        //3、获取token对应的信息
        Map<Object,Object> userMap=stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        //4、判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        //5、提取信息
        UserDTO userDTO=new UserDTO();
        userDTO.setId(Long.parseLong(String.valueOf(userMap.get("id"))));
        userDTO.setNickName((String)userMap.get("nickName"));
        userDTO.setIcon((String)userMap.get("icon"));
        //7、存到UserHolder中去
        UserHolder.saveUser(userDTO);
        //6、重置时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
