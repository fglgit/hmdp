package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //手机号无效
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //随机生成验证码
        String code = RandomUtil.randomNumbers(6);
        //绑定到session
        //session.setAttribute("code",code);
        //扔到redis中，设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送短信验证码成功，验证码："+code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验提交的手机格式
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //提取session中的验证码
        //Object cacheCode=session.getAttribute("code");
        //找redis中的code
        Object cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        String code = (String) cacheCode;
        //验证码失效或者与session对应不上
//        if(cacheCode==null||!code.equals(loginForm.getCode())){
//            return Result.fail("验证码错误");
//        }
        //查找用户，Mybatis-plus实现的
        User user=query().eq("phone",phone).one();

        if(user==null){
            user=createUserWithPhone(phone);
        }

        UserDTO userDTO=new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());
//
//        //保存用户信息到session中
//        //session.setAttribute("user",userDTO);
//        //保存信息到redis总
//        stringRedisTemplate.opsForValue();

        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        Map<String, Object> userMap=new HashMap<>();
        userMap.put("id",String.valueOf(userDTO.getId()));
        userMap.put("nickName",userDTO.getNickName());
        userMap.put("icon",userDTO.getIcon());
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //创建user
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(6));
        //基于mybatis-plus实现
        save(user);
        return user;
    }
}
