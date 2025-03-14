package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    //由于这个类是自己创建的，不是springboot管理的，所以不能自动装载，放入构造器中
    //然后StringRedisTemplate放在上层又springboot管理的类中自动装载即可
    //@Resource
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        HttpSession session = request.getSession();
//        Object user = session.getAttribute("user");
//        String user= stringRedisTemplate.opsForValue().get("user");
//        if (user == null) {
//            response.setStatus(401);
//            System.out.println("拦截成功！！！");
//            return false;
//        }
//        //UserHolder定义了threadlocal的存取操作,存放基本信息UserDTO
//        UserHolder.saveUser((UserDTO)user);
//        return true;

        //1、从请求头获取token
        String token=request.getHeader("authorization");
        //2、判断token是否为空
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }
        //3、获取token对应的信息
        Map<Object,Object> userMap=stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        //4、判断用户是否存在
        if(userMap.isEmpty()){
            response.setStatus(401);
            return false;
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
