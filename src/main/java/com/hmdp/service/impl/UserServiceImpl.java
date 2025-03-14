package com.hmdp.service.impl;

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
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //手机号无效
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //随机生成验证码
        String code = RandomUtil.randomNumbers(6);
        //绑定到session
        session.setAttribute("code",code);

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
        Object cacheCode=session.getAttribute("code");
        String code = (String) cacheCode;
        //验证码失效或者与session对应不上
        if(cacheCode==null||!code.equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }
        //查找用户，Mybatis-plus实现的
        User user=query().eq("phone",phone).one();

        if(user==null){
            user=createUserWithPhone(phone);
        }

        UserDTO userDTO=new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());

        //保存用户信息到session中
        session.setAttribute("user",userDTO);

        return Result.ok();
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
